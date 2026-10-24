package io.blueprints.analysis.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Рекурсия с ветвлением без мемоизации: метод вызывает себя два и более раза на путь.
 *
 * <p>Один самовызов — линейная рекурсия O(n) (факториал, обход списка). Два независимых самовызова
 * в теле — классический экспоненциальный рост (числа Фибоначчи, наивный обход дерева без кеша):
 * O(2ⁿ), и это не лечится микрооптимизациями — нужна мемоизация или динамическое программирование.
 *
 * <p>Считаем именно вызовы <em>в теле</em>, а не в аргументах: {@code f(n - 1)} дважды в одном
 * выражении — всё равно два полных поддерева рекурсии.
 */
public final class ExponentialRecursionRule implements Rule {

    private static final String RULE = "exponential-recursion";
    private static final int BRANCHING_CALLS = 2;

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                int selfCalls = selfCalls(method);
                if (selfCalls >= BRANCHING_CALLS) {
                    findings.add(finding(model, method, selfCalls));
                }
            }
        });
        return findings;
    }

    private static int selfCalls(MethodDeclaration method) {
        String name = method.getNameAsString();
        int arity = method.getParameters().size();
        if (method.getBody().isEmpty()) {
            return 0;
        }
        int calls = 0;
        for (MethodCallExpr call : method.getBody().orElseThrow().findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(name) || call.getArguments().size() != arity) {
                continue;
            }
            boolean self = call.getScope().isEmpty()
                    || call.getScope().filter(ThisExpr.class::isInstance).isPresent()
                    || call.getScope().filter(NameExpr.class::isInstance)
                            .map(scope -> scope.asNameExpr().getNameAsString().equals(method.findAncestor(
                                    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                    .map(type -> type.getNameAsString()).orElse("")))
                            .orElse(false);
            if (self) {
                calls++;
            }
        }
        return calls;
    }

    private static Finding finding(ProjectModel model, MethodDeclaration method, int calls) {
        return Findings.of(model, RULE, Severity.HIGH,
                "Экспоненциальная рекурсия без мемоизации",
                "Метод " + method.getNameAsString() + " вызывает себя " + calls + " раза на путь: число "
                        + "вызовов растёт экспоненциально (O(2ⁿ)), и уже на n = 40 это секунды, а на n = 50 — "
                        + "часы. Запоминайте результаты подзадач (мемоизация) или перепишите на цикл.",
                method, List.of(method),
                new io.blueprints.analysis.Complexity("O(2ⁿ)", "O(n)"),
                new Fix("memoize", "Добавить мемоизацию (кэш по аргументам) или переписать на цикл", null, false),
                "Экспонента превращается в линейный проход по уникальным подзадачам");
    }
}
