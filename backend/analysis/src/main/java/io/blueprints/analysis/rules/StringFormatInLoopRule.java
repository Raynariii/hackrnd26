package io.blueprints.analysis.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
 * Форматирование строки внутри цикла: {@code String.format(...)} на каждой итерации.
 *
 * <p>{@code String.format} разбирает формат и аллоцирует объекты — это заметно дороже обычной
 * конкатенации и уж точно дороже {@code StringBuilder.append}. В цикле такие вызовы накапливаются:
 * типичный источник мусора и просадки на больших объёмах. Иногда формат лучше вынести из цикла совсем.
 */
public final class StringFormatInLoopRule implements Rule {

    private static final String RULE = "format-in-loop";

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!isFormat(call)) {
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

    private static boolean isFormat(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (!("format".equals(name) || "formatted".equals(name))) {
            return false;
        }
        if (call.getArguments().isEmpty()) {
            return false;
        }
        String scope = call.getScope().map(Node::toString).orElse("");
        return scope.equals("String") || scope.endsWith(".String") || "formatted".equals(name);
    }

    private static Finding finding(ProjectModel model, MethodCallExpr call, int depth) {
        BigO current = BigO.of(depth);
        return Findings.of(model, RULE, Severity.MEDIUM,
                "String.format в цикле",
                "Форматирование строки выполняется на каждой итерации: разбор формата и аллокации дороже "
                        + "обычной сборки через StringBuilder, а в цикле это " + current + " вызовов. "
                        + "Соберите строку StringBuilder-ом или вынесите форматирование из цикла.",
                call, List.of(call),
                new Complexity(current.toString(), BigO.constant().toString()),
                new Fix("string-builder", "Заменить форматирование в цикле на StringBuilder или вынести из цикла", null, false),
                "Меньше аллокаций и работы парсера формата на каждой итерации");
    }
}
