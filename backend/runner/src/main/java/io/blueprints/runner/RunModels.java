package io.blueprints.runner;

import java.util.List;

/** DTO внутреннего API песочницы: RunnerRequest / RunnerBatchRequest / RunResult / RunBatchResult в contracts/openapi.yaml. */
public final class RunModels {

    private RunModels() {
    }

    public record SourceFile(String path, String content) {
    }

    public record RunnerRequest(List<SourceFile> files, String mainClass, String stdin, Integer timeoutMs) {
    }

    public record Problem(String file, Integer line, Integer column, String message) {
    }

    public record CompileResult(boolean ok, List<Problem> problems, String output) {
    }

    public record RunResult(CompileResult compile, String stdout, String stderr, Integer exitCode,
                            long timeMs, boolean timedOut) {
    }

    /** RunnerBatchRequest: компиляция один раз, запуск на каждом stdin из {@code inputs}. */
    public record RunnerBatchRequest(List<SourceFile> files, String mainClass, List<String> inputs, Integer timeoutMs) {
    }

    /** RunBatchResult: порядок {@code results} совпадает с порядком входов; при ошибке компиляции — пусто. */
    public record RunBatchResult(CompileResult compile, List<RunResult> results) {
    }
}
