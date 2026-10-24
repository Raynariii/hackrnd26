package io.blueprints.runner;

import io.blueprints.runner.RunModels.RunBatchResult;
import io.blueprints.runner.RunModels.RunResult;
import io.blueprints.runner.RunModels.RunnerBatchRequest;
import io.blueprints.runner.RunModels.RunnerRequest;
import io.blueprints.runner.RunModels.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaSandboxTest {

    private final JavaSandbox sandbox = new JavaSandbox(1 << 20, 60_000, "128m");

    @Test
    void compilesAndRunsWithStdinAndCyrillic() throws Exception {
        String src = """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner in = new Scanner(System.in);
                        int a = in.nextInt(), b = in.nextInt();
                        System.out.println("Сумма: " + (a + b));
                    }
                }
                """;
        RunResult result = sandbox.run(new RunnerRequest(List.of(new SourceFile("Main.java", src)), null, "2 3", 20_000));

        assertThat(result.compile().ok()).isTrue();
        assertThat(result.stdout().trim()).isEqualTo("Сумма: 5");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void reportsCompileErrors() throws Exception {
        RunResult result = sandbox.run(new RunnerRequest(
                List.of(new SourceFile("Main.java", "public class Main { void f() { int x = ; } }")), null, "", 20_000));

        assertThat(result.compile().ok()).isFalse();
        assertThat(result.compile().problems()).isNotEmpty();
        assertThat(result.compile().problems().get(0).file()).isEqualTo("Main.java");
    }

    @Test
    void batchCompilesOnceAndRunsEachInputInOrder() throws Exception {
        String src = """
                import java.util.Scanner;
                public class Main {
                    public static void main(String[] args) {
                        Scanner in = new Scanner(System.in);
                        int n = in.nextInt();
                        if (n < 0) throw new IllegalArgumentException("отрицательное: " + n);
                        System.out.println(n * n);
                    }
                }
                """;
        RunBatchResult batch = sandbox.runBatch(new RunnerBatchRequest(List.of(new SourceFile("Main.java", src)), null,
                List.of("3", "-1", "12"), 20_000));

        assertThat(batch.compile().ok()).isTrue();
        assertThat(batch.results()).hasSize(3);
        assertThat(batch.results().get(0).stdout().trim()).isEqualTo("9");
        assertThat(batch.results().get(1).exitCode()).isEqualTo(1);
        assertThat(batch.results().get(1).stderr()).contains("IllegalArgumentException: отрицательное: -1")
                .doesNotContain("Picked up");
        assertThat(batch.results().get(2).stdout().trim()).isEqualTo("144");
    }

    @Test
    void batchWithCompileErrorHasNoResults() throws Exception {
        RunBatchResult batch = sandbox.runBatch(new RunnerBatchRequest(
                List.of(new SourceFile("Main.java", "public class Main { void f() { int x = ; } }")), null,
                List.of("1", "2"), 20_000));

        assertThat(batch.compile().ok()).isFalse();
        assertThat(batch.compile().output()).doesNotContain("/run-").contains("Main.java:1");
        assertThat(batch.results()).isEmpty();
    }

    @Test
    void killsOnTimeout() throws Exception {
        String src = "public class Main { public static void main(String[] a) { while (true) {} } }";
        RunResult result = sandbox.run(new RunnerRequest(List.of(new SourceFile("Main.java", src)), null, "", 3_000));

        assertThat(result.timedOut()).isTrue();
    }

    @Test
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> sandbox.runBatch(new RunnerBatchRequest(
                List.of(new SourceFile("../Evil.java", "class Evil {}")), null, List.of(""), 20_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Недопустимый путь");
    }

    @Test
    void rejectsOptionInjectionThroughMainClass() {
        assertThatThrownBy(() -> sandbox.runBatch(new RunnerBatchRequest(
                List.of(new SourceFile("Main.java", "public class Main { }")), "-version", List.of(""), 20_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mainClass");
    }

    @Test
    void truncatesHugeOutputWithoutReadingItWhole() throws Exception {
        JavaSandbox small = new JavaSandbox(100, 60_000, "128m");
        String src = """
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("x".repeat(500));
                    }
                }
                """;

        RunResult result = small.runBatch(new RunnerBatchRequest(
                List.of(new SourceFile("Main.java", src)), null, List.of(""), 20_000)).results().get(0);

        assertThat(result.stdout()).hasSizeLessThan(200).contains("[вывод обрезан]");
    }
}
