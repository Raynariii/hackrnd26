package io.blueprints.api.web;

import io.blueprints.core.model.ParseProblem;
import io.blueprints.core.model.SourceFile;

import java.util.List;

/** DTO REST API; имена полей совпадают с contracts/openapi.yaml. */
public final class Dto {

    private Dto() {
    }

    public record CreateProjectRequest(String name, List<SourceFile> files) {
    }

    public record UpdateSourceRequest(int version, List<SourceFile> files) {
    }

    public record VersionedRequest(int version) {
    }

    public record ProjectState(String id, String name, int version, List<SourceFile> files,
                               List<ParseProblem> parseProblems) {
    }

    public record RunProjectRequest(String stdin, Integer version, Integer timeoutMs) {
    }

    public record ErrorResponse(String error, String message, List<ParseProblem> problems) {
    }

    // --- Песочница: RunResult / RunBatchResult (ответы runner, формат как в контракте) ---

    public record CompileInfo(boolean ok, List<ParseProblem> problems, String output) {
    }

    public record RunResult(CompileInfo compile, String stdout, String stderr, Integer exitCode,
                            long timeMs, boolean timedOut) {
    }

    public record RunBatchResult(CompileInfo compile, List<RunResult> results) {
    }

    // --- Проверка поведения: VerifyRequest / VerifyCase / VerifyReport ---

    public record BenchmarkInput(int n, String stdin) {
    }

    public record VerifyRequest(List<String> inputs, Boolean fuzz, Boolean benchmark,
                                List<BenchmarkInput> benchmarkInputs) {
    }

    public record VerifyCase(String name, String input, RunResult expected, RunResult actual, boolean ok,
                             String mismatch) {
    }

    public record BenchmarkPoint(int n, double baselineMs, double currentMs) {
    }

    public record VerifyReport(int baselineVersion, int currentVersion, boolean compiled, boolean behaviorPreserved,
                               int passed, int total, List<VerifyCase> cases, List<BenchmarkPoint> benchmark) {
    }
}
