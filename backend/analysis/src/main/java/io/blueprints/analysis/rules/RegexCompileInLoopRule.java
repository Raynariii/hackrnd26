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
 * {@code Pattern.compile(...)} внутри цикла: регулярное выражение компилируется на каждой итерации.
 *
 * <p>Компиляция регулярки — самая дорогая часть работы с ней, обычно она на порядки дороже самого поиска.
 * В цикле это O(n) компиляций; вынесение в {@code static final Pattern} убирает их полностью.
 * Частая причина «тормозит на большом файле» в реальном коде.
 */
public final class RegexCompileInLoopRule implements Rule {

    private static final String RULE = "regex-compile-in-loop";

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!isPatternCompile(call)) {
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

    private static boolean isPatternCompile(MethodCallExpr call) {
        if (!"compile".equals(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return false;
        }
        String scope = call.getScope().map(Node::toString).orElse("");
        return scope.equals("Pattern") || scope.endsWith(".Pattern") || scope.endsWith("Pattern");
    }

    private static Finding finding(ProjectModel model, MethodCallExpr call, int depth) {
        BigO current = BigO.of(depth);
        return Findings.of(model, RULE, Severity.HIGH,
                "Pattern.compile в цикле",
                "Регулярное выражение компилируется на каждой итерации: компиляция дороже самого поиска, "
                        + "и в цикле это повторяется " + current + " раз. Вынесите Pattern в константу "
                        + "(private static final Pattern …) и переиспользуйте.",
                call, List.of(call),
                new Complexity(current.toString(), BigO.constant().toString()),
                new Fix("static-pattern", "Вынести Pattern в статическую константу", null, false),
                "Компиляция один раз вместо каждой итерации");
    }
}
