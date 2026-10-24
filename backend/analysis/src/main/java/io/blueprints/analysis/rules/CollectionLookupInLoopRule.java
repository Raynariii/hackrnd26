package io.blueprints.analysis.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.BigO;
import io.blueprints.analysis.Complexity;
import io.blueprints.analysis.CostModel;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Линейный поиск по списку внутри цикла: {@code list.contains(x)}, {@code indexOf}, {@code remove(x)}.
 *
 * <p>{@code ArrayList.contains} перебирает элементы — O(n). Внутри цикла это O(n²): типовая причина
 * «тормозит на 10 000 заказов». Лечится индексом: {@code HashSet}/{@code HashMap} дают O(1) на поиск.
 *
 * <p>Правило намеренно срабатывает только при известном типе получателя ({@code List}, {@code ArrayList},
 * {@code LinkedList}, {@code Collection}, …): если тип не выводится из AST, лучше промолчать, чем
 * показать ложную находку.
 */
public final class CollectionLookupInLoopRule implements Rule {

    private static final String RULE = "collection-lookup-in-loop";

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
                if (!costModel.isLinearLookup(call)) {
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
        String receiver = call.getScope().map(Node::toString).orElse("список");
        String preview = "Set<T> index = new HashSet<>(" + receiver + ");\n"
                + "if (index.contains(x)) { ... }";
        return Findings.of(model, RULE, Severity.HIGH,
                "Поиск по списку внутри цикла — O(n²)",
                call.getNameAsString() + "() по списку перебирает элементы: внутри цикла это " + current
                        + ". Индекс в HashSet/HashMap заменяет перебор на O(1), и весь метод становится "
                        + after + ".",
                call, List.of(call),
                new Complexity(current.toString(), after.toString()),
                new Fix("index-by-key",
                        "Построить HashSet/HashMap по коллекции и искать по ключу",
                        preview, true),
                "Убирает линейный поиск на каждой итерации: " + current + " → " + after);
    }
}
