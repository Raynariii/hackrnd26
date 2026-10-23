package io.blueprints.analysis;

public record MethodMetrics(
        String methodId,
        String name,
        String file,
        int lines,
        int cyclomatic,
        int maxNesting,
        String complexity) {
}
