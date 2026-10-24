package io.blueprints.analysis.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.MetricsCalculator;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Глубокая вложенность и высокая цикломатическая сложность метода.
 *
 * <p>Порог вложенности — практический предел читаемости: после 4 уровней (цикл → if → try → if → if)
 * метод перестаёт читаться и почти всегда содержит скрытые ветки. Цикломатическая сложность
 * выше 15 — общепринятый порог «пора дробить» (та же логика, что у checkstyle/pmd).
 */
public final class DeepNestingRule implements Rule {

    private static final String RULE = "deep-nesting";

    private final int maxNesting;
    private final int maxCyclomatic;

    public DeepNestingRule(int maxNesting, int maxCyclomatic) {
        this.maxNesting = maxNesting;
        this.maxCyclomatic = maxCyclomatic;
    }

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                int nesting = MetricsCalculator.maxNesting(method);
                int cyclomatic = MetricsCalculator.cyclomatic(method);
                if (nesting <= maxNesting && cyclomatic <= maxCyclomatic) {
                    continue;
                }
                findings.add(finding(model, method, nesting, cyclomatic));
            }
        });
        return findings;
    }

    private Finding finding(ProjectModel model, MethodDeclaration method, int nesting, int cyclomatic) {
        boolean severe = nesting > maxNesting || cyclomatic > maxCyclomatic * 2;
        StringBuilder explanation = new StringBuilder("Метод ").append(method.getNameAsString());
        if (nesting > maxNesting) {
            explanation.append(": вложенность ").append(nesting).append(" уровня при пороге ").append(maxNesting);
        }
        if (cyclomatic > maxCyclomatic) {
            explanation.append(nesting > maxNesting ? ", " : ": ");
            explanation.append("цикломатическая сложность ").append(cyclomatic).append(" при пороге ").append(maxCyclomatic);
        }
        explanation.append(". Ветки переплетены — выделите части в отдельные методы: это сокращает число путей "
                + "и делает поведение проверяемым тестами.");
        return Findings.of(model, RULE, severe ? Severity.HIGH : Severity.MEDIUM,
                "Высокая вложенность и сложность метода",
                explanation.toString(),
                method, List.of(method),
                null,
                new Fix("extract-method", "Вынести вложенные блоки в отдельные методы", null, false),
                "Число независимых путей падает до сложности отдельных методов");
    }
}
