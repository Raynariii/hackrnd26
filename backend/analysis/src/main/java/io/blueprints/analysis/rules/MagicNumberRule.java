package io.blueprints.analysis.rules;

import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * «Магические» числа в условиях: {@code if (items.size() > 100)}.
 *
 * <p>Число без имени не объясняет смысл границы и повторяется копиями по коду — при изменении
 * бизнес-правила правят не все места. Лечится константой с говорящим именем.
 *
 * <p>Правило смотрит только условия {@code if}/{@code while}/{@code do} и пропускает
 * {@code -1, 0, 1, 2} — это не бизнес-границы, а техника (пустая коллекция, первый индекс).
 */
public final class MagicNumberRule implements Rule {

    private static final String RULE = "magic-number";
    private static final Set<Long> ALLOWED = Set.of(-1L, 0L, 1L, 2L);

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (IfStmt statement : cu.findAll(IfStmt.class)) {
                addFinding(model, statement, statement.getCondition(), findings);
            }
            for (WhileStmt statement : cu.findAll(WhileStmt.class)) {
                addFinding(model, statement, statement.getCondition(), findings);
            }
            for (DoStmt statement : cu.findAll(DoStmt.class)) {
                addFinding(model, statement, statement.getCondition(), findings);
            }
        });
        return findings;
    }

    private static void addFinding(ProjectModel model, Statement statement, Expression condition, List<Finding> out) {
        Set<String> values = new LinkedHashSet<>();
        for (IntegerLiteralExpr literal : condition.findAll(IntegerLiteralExpr.class)) {
            if (!ALLOWED.contains(asLong(literal))) {
                values.add(literal.getValue());
            }
        }
        for (DoubleLiteralExpr literal : condition.findAll(DoubleLiteralExpr.class)) {
            values.add(literal.getValue());
        }
        if (values.isEmpty()) {
            return;
        }
        String list = String.join(", ", values);
        String first = values.iterator().next();
        String constant = "LIMIT_" + first.replace('-', 'M').replace('.', '_');
        String preview = "private static final int " + constant + " = " + first + ";\n"
                + "// в условии: вместо " + list + " — " + constant;
        out.add(Findings.of(model, RULE, Severity.MEDIUM,
                "Магическое число в условии",
                "В условии используются числа без имени: " + list + ". Смысл границы непонятен из кода, "
                        + "а при изменении правила легко поправить не все места — вынесите значения в константы.",
                statement, List.of(statement),
                null,
                new Fix("extract-constant", "Вынести число в именованную константу", preview, true),
                "Граница получает имя и правится в одном месте"));
    }

    private static long asLong(IntegerLiteralExpr literal) {
        try {
            return literal.asNumber().longValue();
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
