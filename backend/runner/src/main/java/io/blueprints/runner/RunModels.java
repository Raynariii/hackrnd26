package io.blueprints.runner;

import java.util.List;

/** DTO внутреннего API песочницы: RunnerRequest / RunResult в contracts/openapi.yaml. */
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
}
