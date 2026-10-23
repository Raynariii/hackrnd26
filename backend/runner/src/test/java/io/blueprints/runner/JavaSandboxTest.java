package io.blueprints.runner;

import io.blueprints.runner.RunModels.RunResult;
import io.blueprints.runner.RunModels.RunnerRequest;
import io.blueprints.runner.RunModels.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void killsOnTimeout() throws Exception {
        String src = "public class Main { public static void main(String[] a) { while (true) {} } }";
        RunResult result = sandbox.run(new RunnerRequest(List.of(new SourceFile("Main.java", src)), null, "", 3_000));

        assertThat(result.timedOut()).isTrue();
    }
}
