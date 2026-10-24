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
import io.blueprints.core.model.ParseProblem;
import io.blueprints.core.model.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    private final BatchRunner runner;
    private final CorpusInputs corpus;
    private final int timeoutMs;
    private final int maxCases;

    public VerifyService(BatchRunner runner, CorpusInputs corpus,
                         @Value("${verify.timeout-ms:10000}") int timeoutMs,
                         @Value("${verify.max-cases:40}") int maxCases) {
        this.runner = runner;
        this.corpus = corpus;
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

        RunBatchResult expected = runner.runBatch(baseline, stdins, timeoutMs);
        if (!expected.compile().ok()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "baseline_compile_error",
                    "Эталонная версия " + baselineVersion + " не компилируется — сравнивать не с чем",
                    problems(expected));
        }
        RunBatchResult actual = runner.runBatch(current, stdins, timeoutMs);

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
