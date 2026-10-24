package io.blueprints.analysis;

import com.github.javaparser.ast.Node;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Сборка {@link Finding}: файл и диапазон строк берутся из узла {@code at}, ссылки на узлы графа —
 * по соглашению {@code "g" + astId} из {@code contracts/graph.schema.json}.
 *
 * <p>Один хелпер на все правила — чтобы формат находки совпадал с {@code Finding} в
 * {@code contracts/openapi.yaml} и не расходился между правилами.
 */
public final class Findings {

    private Findings() {
    }

    public static Finding of(ProjectModel model, String ruleId, Severity severity, String title, String explanation,
                             Node at, List<Node> nodes, Complexity complexity, Fix fix, String expectedEffect) {
        int start = Ast.startLine(at);
        int end = Ast.endLine(at);
        List<String> nodeIds = new ArrayList<>();
        for (Node node : nodes) {
            String nodeId = Ast.nodeId(model, node);
            if (nodeId != null && !nodeIds.contains(nodeId)) {
                nodeIds.add(nodeId);
            }
        }
        String anchor = nodeIds.isEmpty() ? "L" + start : nodeIds.get(0);
        return new Finding(
                ruleId + ":" + anchor,
                ruleId,
                severity,
                title,
                explanation,
                Ast.fileOf(model, at),
                start,
                end,
                nodeIds,
                complexity,
                fix,
                expectedEffect);
    }

    public static Finding of(ProjectModel model, String ruleId, Severity severity, String title, String explanation,
                             Node at, List<Node> nodes, Complexity complexity, Fix fix) {
        return of(model, ruleId, severity, title, explanation, at, nodes, complexity, fix, null);
    }
}
