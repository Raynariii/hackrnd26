package io.blueprints.core.graph;

public record GraphEdge(String id, String from, String to, EdgeKind kind, String label) {
}
