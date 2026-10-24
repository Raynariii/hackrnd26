package io.blueprints.analysis.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.BigO;
import io.blueprints.analysis.Complexity;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Вложенные циклы по одной и той же коллекции или массиву — классический O(n²).
 *
 * <p>Ловим два вида: {@code for (Order a : orders) { for (Order b : orders) }} и
 * {@code for (int i = 0; i < arr.length; i++) { for (int j = 0; j < arr.length; j++) }} —
 * границы сравниваются по нормализованному выражению ({@code arr.length}, {@code orders.size()}).
 *
 * <p>Находка ставится на внутренний цикл: именно он даёт лишний проход. Ссылки ведут
 * на оба цикла, чтобы подсветить пару на блок-схеме.
 */
public final class NestedLoopSameCollectionRule implements Rule {

    private static final String RULE = "nested-loop-same-collection";

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (ForEachStmt loop : cu.findAll(ForEachStmt.class)) {
                String source = Ast.compact(loop.getIterable().toString());
                Node outer = outermostMatching(loop, source);
                if (outer != null) {
                    findings.add(finding(model, outer, loop));
                }
            }
            for (ForStmt loop : cu.findAll(ForStmt.class)) {
                String source = Ast.boundOperand(loop);
                if (source == null) {
                    continue;
                }
                Node outer = outermostMatching(loop, source);
                if (outer != null) {
                    findings.add(finding(model, outer, loop));
                }
            }
        });
        return findings;
    }

    private static Node outermostMatching(Node loop, String source) {
        for (Node candidate : Ast.enclosingLoops(loop)) {
            if (source.equals(Ast.iterationSource(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static Finding finding(ProjectModel model, Node outer, Node inner) {
        // loopDepth считает только циклы-предки: сам внутренний цикл даёт ещё один уровень.
        int depth = Ast.loopDepth(inner) + 1;
        BigO current = BigO.of(depth);
        BigO after = BigO.of(depth - 1);
        return Findings.of(model, RULE, Severity.HIGH,
                "Вложенные циклы по одной коллекции",
                "Внутренний цикл заново перебирает ту же коллекцию, что и внешний: число операций растёт "
                        + "как произведение размеров — " + current + ". Такой перебор заменяется одним проходом "
                        + "с HashSet/HashMap (индекс по ключу) или сортировкой.",
                inner, List.of(outer, inner),
                new Complexity(current.toString(), after.toString()),
                new Fix("index-by-key",
                        "Собрать элементы в HashSet/HashMap и убрать внутренний перебор",
                        null, false),
                "Убирает лишний проход: " + current + " → " + after);
    }
}
