package io.blueprints.analysis.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/** Пример правила: метод длиннее порога → предложить выделить части. */
public final class LongMethodRule implements Rule {

    private final int maxLines;

    public LongMethodRule(int maxLines) {
        this.maxLines = maxLines;
    }

    @Override
    public String id() {
        return "long-method";
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                method.getRange().ifPresent(r -> {
                    int lines = r.end.line - r.begin.line + 1;
                    if (lines > maxLines) {
                        String nodeId = "g" + model.idOf(method);
                        findings.add(new Finding(
                                id() + ":" + nodeId,
                                id(),
                                lines > maxLines * 2 ? Severity.HIGH : Severity.MEDIUM,
                                "Длинный метод",
                                "Метод " + method.getNameAsString() + " содержит " + lines
                                        + " строк. Рекомендуется выделить часть логики в отдельные методы.",
                                file, r.begin.line, r.end.line,
                                List.of(nodeId),
                                null,
                                new Fix("extract-method", "Выделить блоки в отдельные методы", null, false),
                                null));
                    }
                });
            }
        });
        return findings;
    }
}
