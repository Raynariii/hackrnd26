package io.blueprints.analysis.rules;

import com.github.javaparser.ast.stmt.CatchClause;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Пустой {@code catch}: исключение проглатывается молча.
 *
 * <p>Такой блок скрывает ошибки: программа продолжает работу в неверном состоянии, а причина
 * не видна ни в консоли, ни в логах. Лечится логированием и/или повторным выбрасыванием.
 */
public final class EmptyCatchRule implements Rule {

    private static final String RULE = "empty-catch";

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (CatchClause clause : cu.findAll(CatchClause.class)) {
                if (!clause.getBody().getStatements().isEmpty()) {
                    continue;
                }
                String parameter = clause.getParameter().getNameAsString();
                String preview = "catch (" + clause.getParameter().getType().asString() + " " + parameter + ") {\n"
                        + "    log.warn(\"не удалось выполнить операцию\", " + parameter + ");\n"
                        + "    throw new IllegalStateException(" + parameter + ");\n"
                        + "}";
                findings.add(Findings.of(model, RULE, Severity.HIGH,
                        "Пустой catch — исключение проглатывается",
                        "Блок catch (" + parameter + ") пуст: ошибка не видна ни в логах, ни в консоли, "
                                + "и программа продолжает работу с неверным состоянием. Залогируйте исключение "
                                + "или пробросьте его выше.",
                        clause, List.of(clause),
                        null,
                        new Fix("log-or-rethrow", "Залогировать или пробросить исключение", preview, false),
                        "Ошибки становятся видимыми, поведение при сбое — предсказуемым"));
            }
        });
        return findings;
    }
}
