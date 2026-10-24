package io.blueprints.api.verify;

import io.blueprints.api.runner.BatchRunner;
import io.blueprints.api.web.ApiException;
import io.blueprints.api.web.Dto.BenchmarkInput;
import io.blueprints.api.web.Dto.CompileInfo;
import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.api.web.Dto.RunResult;
import io.blueprints.api.web.Dto.VerifyCase;
import io.blueprints.api.web.Dto.VerifyReport;
import io.blueprints.api.web.Dto.VerifyRequest;
import io.blueprints.core.model.ParseProblem;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Логика verify с поддельной песочницей: «программа» — функция stdin → результат. Реальный запуск javac/java
 * проверяется в runner (JavaSandboxTest) и сквозным прогоном api + runner.
 */
class VerifyServiceTest {

    private static final List<SourceFile> V1 = List.of(new SourceFile("Main.java", "class Main { /* v1 */ }"));
    private static final List<SourceFile> V2 = List.of(new SourceFile("Main.java", "class Main { /* v2 */ }"));
    private static final CompileInfo OK = new CompileInfo(true, List.of(), "");

    @TempDir
    Path corpusDir;

    /** Песочница: по содержимому файла выбирает «программу». */
    private BatchRunner fake(Function<String, RunResult> v1, Function<String, RunResult> v2, List<Integer> calls) {
        return (files, inputs, timeoutMs) -> {
            synchronized (calls) { // эталон и текущая версия запускаются параллельно
                calls.add(inputs.size());
            }
            if (files.get(0).content().contains("broken")) {
                return new RunBatchResult(new CompileInfo(false,
                        List.of(new ParseProblem("Main.java", 3, null, "';' expected")), "ошибка"), List.of());
            }
            Function<String, RunResult> program = files.get(0).content().contains("v1") ? v1 : v2;
            return new RunBatchResult(OK, inputs.stream().map(program).toList());
        };
    }

    private static RunResult out(String stdout) {
        return new RunResult(OK, stdout, "", 0, 5, false);
    }

    private VerifyService service(BatchRunner runner) {
        return new VerifyService(runner, new CorpusInputs(corpusDir.toString()), 10_000, 40);
    }

    @Test
    void sameBehaviorPassesAllCasesWithOneBatchPerVersion() {
        Function<String, RunResult> echo = in -> out("Привет, " + in.strip() + "\n");
        List<Integer> calls = new ArrayList<>();

        VerifyReport report = service(fake(echo, echo, calls))
                .verify(V1, 1, V2, 3, List.of("Мир"), new VerifyRequest(List.of("Анна"), null, null, null));

        assertThat(report.compiled()).isTrue();
        assertThat(report.behaviorPreserved()).isTrue();
        assertThat(report.baselineVersion()).isEqualTo(1);
        assertThat(report.currentVersion()).isEqualTo(3);
        assertThat(report.total()).isEqualTo(2 + VerifyService.EDGE_CASES.size());
        assertThat(report.passed()).isEqualTo(report.total());
        assertThat(report.cases()).extracting(VerifyCase::name).startsWith("Вход №1", "Ввод из запуска №1");
        assertThat(calls).hasSize(2); // javac — по разу на версию, а не на каждый вход
    }

    @Test
    void reportsWhatExactlyDiffers() {
        Function<String, RunResult> v1 = in -> out("итог: 10\nготово\n");
        Function<String, RunResult> v2 = in -> new RunResult(OK, "итог: 11\nготово\n",
                "Exception in thread \"main\" java.lang.ArithmeticException: / by zero\n\tat Main.main(Main.java:5)", 1, 5, false);

        VerifyReport report = service(fake(v1, v2, new ArrayList<>()))
                .verify(V1, 1, V2, 2, List.of(), new VerifyRequest(List.of("1"), false, null, null));

        assertThat(report.behaviorPreserved()).isFalse();
        assertThat(report.passed()).isZero();
        VerifyCase c = report.cases().get(0);
        assertThat(c.mismatch())
                .contains("stdout: строка 1: ожидалось «итог: 10», получено «итог: 11»")
                .contains("stderr:")
                .contains("exitCode: ожидался 0, получен 1");
    }

    @Test
    void identityHashesAndStackTraceLinesAreNotDifferences() {
        Function<String, RunResult> v1 = in -> new RunResult(OK, "Order@1b6d3586\r\n",
                "java.lang.IllegalStateException: нет заказов\n\tat Main.a(Main.java:10)\n", 1, 5, false);
        Function<String, RunResult> v2 = in -> new RunResult(OK, "Order@7852e922\n",
                "java.lang.IllegalStateException: нет заказов\n\tat Main.b(Main.java:31)\n\tat Main.a(Main.java:12)\n", 1, 5, false);

        VerifyReport report = service(fake(v1, v2, new ArrayList<>()))
                .verify(V1, 1, V2, 2, List.of(), new VerifyRequest(List.of("x"), false, null, null));

        assertThat(report.behaviorPreserved()).isTrue();
    }

    @Test
    void currentCompileErrorFailsEveryCase() {
        List<SourceFile> broken = List.of(new SourceFile("Main.java", "class Main { broken }"));
        VerifyReport report = service(fake(in -> out("1"), in -> out("1"), new ArrayList<>()))
                .verify(V1, 1, broken, 2, List.of(), new VerifyRequest(List.of("a", "b"), false, null, null));

        assertThat(report.compiled()).isFalse();
        assertThat(report.behaviorPreserved()).isFalse();
        assertThat(report.total()).isEqualTo(2);
        assertThat(report.cases().get(0).mismatch()).contains("не компилируется").contains("Main.java:3");
    }

    @Test
    void baselineCompileErrorIsUnprocessable() {
        List<SourceFile> broken = List.of(new SourceFile("Main.java", "class Main { broken }"));
        assertThatThrownBy(() -> service(fake(in -> out("1"), in -> out("1"), new ArrayList<>()))
                .verify(broken, 1, V2, 2, List.of(), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Эталонная версия 1 не компилируется");
    }

    @Test
    void generatedInputsWhereBaselineHangsAreSkipped() {
        Function<String, RunResult> hangsOnEmpty = in -> in.isEmpty()
                ? new RunResult(OK, "", "", null, 10_000, true) : out("ok\n");
        VerifyReport report = service(fake(hangsOnEmpty, hangsOnEmpty, new ArrayList<>()))
                .verify(V1, 1, V2, 2, List.of(), new VerifyRequest(List.of("1"), true, null, null));

        assertThat(report.cases()).extracting(VerifyCase::name).doesNotContain("Граничный: пустой ввод");
        assertThat(report.behaviorPreserved()).isTrue();
    }

    @Test
    void currentVersionTimeoutIsAFailure() {
        VerifyReport report = service(fake(in -> out("1\n"), in -> new RunResult(OK, "", "", null, 10_000, true),
                new ArrayList<>())).verify(V1, 1, V2, 2, List.of(), new VerifyRequest(List.of("1"), false, null, null));

        assertThat(report.behaviorPreserved()).isFalse();
        assertThat(report.cases().get(0).mismatch()).startsWith("timeout:");
    }

    @Test
    void corpusInputsAndBenchFilesAreUsedForMatchingProject() throws Exception {
        Path example = Files.createDirectories(corpusDir.resolve("01-orders"));
        Files.writeString(example.resolve("Main.java"), "class Main { /* v1 */ }\r\n");
        Path inputs = Files.createDirectories(example.resolve("inputs"));
        Files.writeString(inputs.resolve("01.txt"), "3\n1 2 3\n");
        Files.writeString(inputs.resolve("bench-1000.txt"), "1000\n");
        Files.writeString(inputs.resolve("bench-500.txt"), "500\n");

        Function<String, RunResult> v1 = in -> new RunResult(OK, "sum\n", "", 0, in.length() * 10L, false);
        Function<String, RunResult> v2 = in -> new RunResult(OK, "sum\n", "", 0, in.length(), false);
        VerifyReport report = service(fake(v1, v2, new ArrayList<>()))
                .verify(V1, 1, V2, 2, List.of(), new VerifyRequest(null, false, true, null));

        assertThat(report.cases()).extracting(VerifyCase::name)
                .containsExactly("corpus/01-orders/inputs/01.txt", "Бенчмарк: n = 500", "Бенчмарк: n = 1000");
        assertThat(report.benchmark()).extracting(b -> b.n()).containsExactly(500, 1000);
        assertThat(report.benchmark().get(1).currentMs()).isLessThan(report.benchmark().get(1).baselineMs());
        assertThat(report.behaviorPreserved()).isTrue();
    }

    @Test
    void explicitBenchmarkInputsOverrideCorpus() {
        VerifyReport report = service(fake(in -> out("x"), in -> out("x"), new ArrayList<>()))
                .verify(V1, 1, V2, 2, List.of(), new VerifyRequest(List.of("1"), false, true,
                        List.of(new BenchmarkInput(4, "4"), new BenchmarkInput(2, "2"))));

        assertThat(report.benchmark()).extracting(b -> b.n()).containsExactly(2, 4);
    }

    @Test
    void duplicateInputsAreTestedOnce() {
        VerifyReport report = service(fake(in -> out("x"), in -> out("x"), new ArrayList<>()))
                .verify(V1, 1, V2, 2, List.of("1"), new VerifyRequest(List.of("1", "1", "0\n"), true, null, null));

        assertThat(report.cases()).extracting(VerifyCase::input).doesNotHaveDuplicates();
    }
}
