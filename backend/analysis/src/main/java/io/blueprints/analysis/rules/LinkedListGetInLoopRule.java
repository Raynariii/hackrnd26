package io.blueprints.analysis.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.BigO;
import io.blueprints.analysis.Complexity;
import io.blueprints.analysis.CostModel;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.FixSafety;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Обращение к {@code LinkedList} по индексу в цикле: {@code list.get(i)}.
 *
 * <p>{@code LinkedList} не хранит массив: {@code get(i)} идёт от начала списка, то есть O(n), а в цикле —
 * O(n²). Обход через for-each или {@code Iterator} — O(n).
 */
public final class LinkedListGetInLoopRule implements Rule {

    private static final String RULE = "linked-list-index-access";

    private final CostModel costModel = new CostModel();

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!costModel.isLinearIndexAccess(call)) {
                    continue;
                }
                int depth = Ast.loopDepth(call);
                if (depth == 0) {
                    continue;
                }
                findings.add(finding(model, call, depth));
            }
        });
        return findings;
    }

    private static Finding finding(ProjectModel model, MethodCallExpr call, int depth) {
        BigO current = BigO.of(depth + 1);
        BigO after = BigO.of(depth);
        String receiver = call.getScope().map(Object::toString).orElse("list");
        String preview = "for (T item : " + receiver + ") {\n    // вместо " + receiver + ".get(i)\n}";
        String notApplicable = FixSafety.explain(RULE, call);
        return Findings.of(model, RULE, Severity.HIGH,
                "LinkedList.get(i) в цикле — O(n²)",
                "У LinkedList доступ по индексу идёт от головы списка и стоит O(n). В цикле это " + current
                        + "; обход через for-each/Iterator — " + after + ".",
                call, List.of(call),
                new Complexity(current.toString(), after.toString()),
                new Fix("iterator",
                        notApplicable != null ? notApplicable : "Заменить обращение по индексу на обход Iterator/for-each",
                        preview, notApplicable == null),
                "Убирает проход по списку на каждой итерации: " + current + " → " + after);
    }
}
