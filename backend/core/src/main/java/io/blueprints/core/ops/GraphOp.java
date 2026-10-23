package io.blueprints.core.ops;

import io.blueprints.core.graph.NodeKind;

import java.util.List;

/** Операция над графом; поля — как GraphOpRequest в contracts/openapi.yaml. */
public record GraphOp(
        String op,
        String nodeId,
        List<String> nodeIds,
        String code,
        NodeKind kind,
        String targetId,
        String name) {
}
