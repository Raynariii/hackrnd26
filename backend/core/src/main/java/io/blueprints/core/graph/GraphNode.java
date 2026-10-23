package io.blueprints.core.graph;

import java.util.List;
import java.util.Map;

public record GraphNode(
        String id,
        String astId,
        NodeKind kind,
        String label,
        String code,
        SourceRange range,
        String parent,
        List<String> findings,
        Map<String, Object> meta) {
}
