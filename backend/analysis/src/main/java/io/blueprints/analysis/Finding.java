package io.blueprints.analysis;

import java.util.List;

/** Находка анализатора; формат — Finding в contracts/openapi.yaml. */
public record Finding(
        String id,
        String rule,
        Severity severity,
        String title,
        String explanation,
        String file,
        int startLine,
        int endLine,
        List<String> nodeIds,
        Complexity complexity,
        Fix fix,
        String expectedEffect) {
}
