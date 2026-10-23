package io.blueprints.core.graph;

import java.util.List;

/** Проекция AST в граф. Формат — contracts/graph.schema.json. */
public record Graph(GraphLevel level, String scope, List<GraphNode> nodes, List<GraphEdge> edges) {
}
