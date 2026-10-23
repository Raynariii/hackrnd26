package io.blueprints.core.graph;

public record SourceRange(String file, int startLine, int endLine, Integer startColumn, Integer endColumn) {
}
