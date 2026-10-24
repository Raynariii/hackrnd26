package io.blueprints.analysis.fixes;

import com.github.javaparser.ast.Node;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.ProjectModel;

/** Общие операции над находкой: узел-якорь и текст отказа «только вручную». */
public final class FixAnchors {

    private FixAnchors() {
    }

    /**
     * Узел, к которому привязана находка: первый id из {@code Finding.nodeIds}. По соглашению
     * {@code contracts/graph.schema.json} это {@code "g" + astId}.
     */
    public static Node anchor(ProjectModel model, Finding finding) {
        if (finding.nodeIds().isEmpty()) {
            throw new FixNotApplicableException("У находки нет привязки к узлу кода");
        }
        String nodeId = finding.nodeIds().get(0);
        return model.nodeById(nodeId.substring(1))
                .orElseThrow(() -> new FixNotApplicableException(
                        "Узел " + nodeId + " не найден в текущей версии"));
    }

    /**
     * Стандартный отказ, когда исправление не подходит к этому узлу или требует решений,
     * которых нет в детерминированном правиле.
     */
    public static String manual(Finding finding) {
        return "Исправление «" + finding.fix().description()
                + "» требует ручной правки: автоматически менять код небезопасно";
    }
}
