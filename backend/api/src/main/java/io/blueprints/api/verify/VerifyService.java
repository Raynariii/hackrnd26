package io.blueprints.api.verify;

import io.blueprints.api.runner.BatchRunner;
import io.blueprints.api.web.ApiException;
import io.blueprints.api.web.Dto.BenchmarkInput;
import io.blueprints.api.web.Dto.BenchmarkPoint;
import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.api.web.Dto.RunResult;
import io.blueprints.api.web.Dto.VerifyCase;
import io.blueprints.api.web.Dto.VerifyReport;
import io.blueprints.api.web.Dto.VerifyRequest;
import io.blueprints.core.fuzz.FuzzHarness;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ParseProblem;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Дифференциальные тесты (D3): эталонная версия и текущая компилируются по одному разу и запускаются в песочнице
 * на одних и тех же stdin-входах; stdout, stderr и код выхода сравниваются после {@link OutputNormalizer}.
 */
@Service
public class VerifyService {

    /** Граничные входы для программ на Scanner/stdin (G5, v1 — без фаззинга методов). */
    static final List<Input> EDGE_CASES = List.of(
            new Input("Граничный: пустой ввод", "", true),
            new Input("Граничный: пустая строка", "\n", true),
            new Input("Граничный: ноль", "0\n", true),
            new Input("Граничный: отрицательное число", "-1\n", true),
            new Input("Граничный: Integer.MAX_VALUE", "2147483647\n", true),
            new Input("Граничный: не число", "abc\n", true));

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);
    /** Вызовов на один статический метод при фаззинге (G5). */
    static final int FUZZ_CASES = 20;
    static final String FUZZ_CASE_PREFIX = "Фаззинг статических методов";
    private static final java.util.regex.Pattern FUZZ_CALL = java.util.regex.Pattern.compile("^([\\w$]+)\\(");

    private final BatchRunner runner;
    private final CorpusInputs corpus;
    private final JavaProjectParser parser;
    private final int timeoutMs;
    private final int maxCases;

    public VerifyService(BatchRunner runner, CorpusInputs corpus, JavaProjectParser parser,
                         @Value("${verify.timeout-ms:10000}") int timeoutMs,
                         @Value("${verify.max-cases:40}") int maxCases) {
        this.runner = runner;
        this.corpus = corpus;
        this.parser = parser;
        this.timeoutMs = timeoutMs;
        this.maxCases = maxCases;
    }

    record Input(String name, String stdin, boolean generated) {
    }

    public VerifyReport verify(List<SourceFile> baseline, int baselineVersion,
                               List<SourceFile> current, int currentVersion,
                               List<String> savedInputs, VerifyRequest request) {
        VerifyRequest r = request == null ? new VerifyRequest(null, null, null, null) : request;
        Optional<CorpusInputs.Example> example = corpus.find(baseline);

        List<Input> inputs = collectInputs(r, savedInputs, example);
        List<BenchmarkInput> bench = Boolean.TRUE.equals(r.benchmark()) ? benchmarkInputs(r, example) : List.of();

        List<String> stdins = new ArrayList<>(inputs.stream().map(Input::stdin).toList());
        bench.forEach(b -> stdins.add(b.stdin()));

        // Эталон и текущая версия — параллельно (два независимых javac+java, у runner 2 CPU). С бенчмарком —
        // последовательно, иначе процессы делят процессор и время «до/после» искажается.
        RunBatchResult expected;
        RunBatchResult actual;
        if (bench.isEmpty()) {
            CompletableFuture<RunBatchResult> pending =
                    CompletableFuture.supplyAsync(() -> runner.runBatch(current, stdins, timeoutMs));
            expected = runner.runBatch(baseline, stdins, timeoutMs);
            actual = join(pending);
        } else {
            expected = runner.runBatch(baseline, stdins, timeoutMs);
            actual = expected.compile().ok() ? runner.runBatch(current, stdins, timeoutMs) : null;
        }
        if (!expected.compile().ok()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "baseline_compile_error",
                    "Эталонная версия " + baselineVersion + " не компилируется — сравнивать не с чем",
                    problems(expected));
        }
        if (!actual.compile().ok()) {
            String reason = "Текущая версия не компилируется" + firstProblem(actual);
            List<VerifyCase> cases = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                Input in = inputs.get(i);
                RunResult exp = expected.results().get(i);
                if (in.generated() && exp.timedOut()) {
                    continue;
                }
                cases.add(new VerifyCase(in.name(), in.stdin(), exp, null, false, "compile: " + reason));
            }
            return new VerifyReport(baselineVersion, currentVersion, false, false, 0, cases.size(), cases, List.of());
        }

        List<VerifyCase> cases = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            RunResult exp = expected.results().get(i);
            if (in.generated() && exp.timedOut()) {
                continue; // эталон сам не завершился на сгенерированном входе — такой вход ничего не доказывает
            }
            cases.add(compare(in.name(), in.stdin(), exp, actual.results().get(i)));
        }

        if (!Boolean.FALSE.equals(r.fuzz())) {
            fuzzCase(baseline, current).ifPresent(cases::add);
        }

        List<BenchmarkPoint> benchmark = new ArrayList<>();
        for (int j = 0; j < bench.size(); j++) {
            int idx = inputs.size() + j;
            RunResult exp = expected.results().get(idx);
            RunResult act = actual.results().get(idx);
            BenchmarkInput b = bench.get(j);
            cases.add(compare("Бенчмарк: n = " + b.n(), abbreviate(b.stdin()), exp, act));
            benchmark.add(new BenchmarkPoint(b.n(), exp.timeMs(), act.timeMs()));
        }

        int passed = (int) cases.stream().filter(VerifyCase::ok).count();
        boolean preserved = !cases.isEmpty() && passed == cases.size();
        return new VerifyReport(baselineVersion, currentVersion, true, preserved, passed, cases.size(), cases, benchmark);
    }

    /**
     * Фаззинг статических методов (G5): обвязка {@link FuzzHarness} вызывает каждый общий для обеих версий
     * статический метод на одних и тех же детерминированных аргументах; вывод сравнивается целиком. Это ловит
     * изменение поведения метода, до которого не дотягиваются stdin-входы. Сбой обвязки не роняет verify —
     * фаззинг дополнение к основным случаям.
     */
    Optional<VerifyCase> fuzzCase(List<SourceFile> baseline, List<SourceFile> current) {
        try {
            ProjectModel before = parser.parse(baseline);
            ProjectModel after = parser.parse(current);
            if (before.hasErrors() || after.hasErrors()) {
                return Optional.empty();
            }
            List<FuzzHarness.Target> targets = FuzzHarness.common(before, after);
            if (targets.isEmpty()) {
                return Optional.empty();
            }
            FuzzHarness.Harness harness = FuzzHarness.generate(targets, FUZZ_CASES);
            List<SourceFile> withHarnessBefore = new ArrayList<>(baseline);
            withHarnessBefore.add(harness.file());
            List<SourceFile> withHarnessAfter = new ArrayList<>(current);
            withHarnessAfter.add(harness.file());
            int fuzzTimeout = Math.max(timeoutMs, 30_000);
            CompletableFuture<RunBatchResult> pending = CompletableFuture.supplyAsync(
                    () -> runner.runBatch(withHarnessAfter, harness.mainClass(), List.of(""), fuzzTimeout));
            RunBatchResult exp = runner.runBatch(withHarnessBefore, harness.mainClass(), List.of(""), fuzzTimeout);
            RunBatchResult act = join(pending);
            if (exp == null || act == null || !exp.compile().ok() || !act.compile().ok()
                    || exp.results().isEmpty() || act.results().isEmpty() || exp.results().get(0).timedOut()) {
                log.warn("Фаззинг пропущен: обвязка не скомпилировалась или эталон не завершился");
                return Optional.empty();
            }
            String name = FUZZ_CASE_PREFIX + ": " + targets.size() + " × " + FUZZ_CASES + " вызовов";
            VerifyCase c = compare(name, null, exp.results().get(0), act.results().get(0));
            if (!c.ok()) {
                c = new VerifyCase(c.name(), null, c.expected(), c.actual(), false,
                        culprit(exp.results().get(0).stdout(), act.results().get(0).stdout()) + c.mismatch());
            }
            return Optional.of(c);
        } catch (RuntimeException e) {
            log.warn("Фаззинг пропущен: {}", e.toString());
            return Optional.empty();
        }
    }

    /** «метод pairs: » — метод, на вызове которого вывод впервые разошёлся (по строке «имя(аргументы) -> …»). */
    static String culprit(String expectedStdout, String actualStdout) {
        String[] e = OutputNormalizer.normalizeStdout(expectedStdout).split("\n", -1);
        String[] a = OutputNormalizer.normalizeStdout(actualStdout).split("\n", -1);
        int i = 0;
        while (i < e.length && i < a.length && e[i].equals(a[i])) {
            i++;
        }
        for (int k = Math.min(i, e.length - 1); k >= 0; k--) {
            java.util.regex.Matcher m = FUZZ_CALL.matcher(e[k]);
            if (m.find()) {
                return "метод " + m.group(1) + ": ";
            }
        }
        return "";
    }

    private static RunBatchResult join(CompletableFuture<RunBatchResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    List<Input> collectInputs(VerifyRequest r, List<String> savedInputs, Optional<CorpusInputs.Example> example) {
        Map<String, Input> unique = new LinkedHashMap<>();
        List<Input> all = new ArrayList<>();
        if (r.inputs() != null) {
            for (int i = 0; i < r.inputs().size(); i++) {
                all.add(new Input("Вход №" + (i + 1), Objects.requireNonNullElse(r.inputs().get(i), ""), false));
            }
        }
        example.ifPresent(ex -> ex.inputs().forEach(in -> all.add(new Input(in.name(), in.stdin(), false))));
        if (savedInputs != null) {
            for (int i = 0; i < savedInputs.size(); i++) {
                all.add(new Input("Ввод из запуска №" + (i + 1), savedInputs.get(i), false));
            }
        }
        if (!Boolean.FALSE.equals(r.fuzz())) {
            all.addAll(EDGE_CASES);
        }
        for (Input in : all) {
            if (unique.size() >= maxCases) {
                break;
            }
            unique.putIfAbsent(in.stdin(), in);
        }
        return List.copyOf(unique.values());
    }

    private static List<BenchmarkInput> benchmarkInputs(VerifyRequest r, Optional<CorpusInputs.Example> example) {
        if (r.benchmarkInputs() != null && !r.benchmarkInputs().isEmpty()) {
            return r.benchmarkInputs().stream().sorted((a, b) -> Integer.compare(a.n(), b.n())).toList();
        }
        return example.map(ex -> ex.bench().stream().map(b -> new BenchmarkInput(b.n(), b.stdin())).toList())
                .orElse(List.of());
    }

    static VerifyCase compare(String name, String input, RunResult expected, RunResult actual) {
        List<String> mismatches = new ArrayList<>();
        if (expected.timedOut() || actual.timedOut()) {
            if (expected.timedOut() != actual.timedOut()) {
                mismatches.add("timeout: " + (actual.timedOut() ? "текущая версия не уложилась в лимит времени"
                        : "эталон не уложился в лимит времени, текущая версия — уложилась"));
            } else {
                mismatches.add("timeout: обе версии не уложились в лимит времени — поведение не проверено");
            }
        } else {
            String expOut = OutputNormalizer.normalizeStdout(expected.stdout());
            String actOut = OutputNormalizer.normalizeStdout(actual.stdout());
            if (!expOut.equals(actOut)) {
                mismatches.add("stdout: " + firstDifference(expOut, actOut));
            }
            String expErr = OutputNormalizer.normalizeStderr(expected.stderr());
            String actErr = OutputNormalizer.normalizeStderr(actual.stderr());
            if (!expErr.equals(actErr)) {
                mismatches.add("stderr: " + firstDifference(expErr, actErr));
            }
            if (!Objects.equals(expected.exitCode(), actual.exitCode())) {
                mismatches.add("exitCode: ожидался " + expected.exitCode() + ", получен " + actual.exitCode());
            }
        }
        return new VerifyCase(name, input, expected, actual, mismatches.isEmpty(),
                mismatches.isEmpty() ? null : String.join("; ", mismatches));
    }

    /** «строка 3: ожидалось «…», получено «…»» — чтобы в панели «Тесты» сразу было видно, что разошлось. */
    static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        int n = Math.max(e.length, a.length);
        for (int i = 0; i < n; i++) {
            String el = i < e.length ? e[i] : null;
            String al = i < a.length ? a[i] : null;
            if (!Objects.equals(el, al)) {
                return "строка " + (i + 1) + ": ожидалось " + quote(el) + ", получено " + quote(al);
            }
        }
        return "различаются";
    }

    private static String quote(String line) {
        return line == null ? "(нет строки)" : "«" + abbreviate(line) + "»";
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "…";
    }

    private static String firstProblem(RunBatchResult result) {
        List<ParseProblem> problems = result.compile().problems();
        if (problems == null || problems.isEmpty()) {
            return "";
        }
        ParseProblem p = problems.get(0);
        return ": " + p.file() + (p.line() == null ? "" : ":" + p.line()) + " — " + p.message();
    }

    private static List<ParseProblem> problems(RunBatchResult result) {
        return result.compile().problems() == null ? List.of() : result.compile().problems();
    }
}
