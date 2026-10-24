package io.blueprints.analysis.fixes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixSafety;
import io.blueprints.core.model.ProjectModel;

/** {@code s += x} в цикле → {@code StringBuilder} до цикла и {@code s = sb.toString()} после. */
final class StringBuilderFixer implements Fixer {

    @Override
    public String rule() {
        return "string-concat-in-loop";
    }

    @Override
    public String kind() {
        return "string-builder";
    }

    @Override
    public String explain(Finding finding, ProjectModel model) {
        return FixSafety.explain(rule(), FixSupport.anchor(model, finding));
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        AssignExpr assign = (AssignExpr) FixSupport.anchor(model, finding);
        Node loop = FixSupport.nearestLoop(assign);
        String variable = FixSafety.assignTargetName(assign);
        String builder = FixSupport.uniqueName(assign, "_sb");
        FixSupport.insertBefore(FixSupport.loopStatement(loop), StaticJavaParser.parseStatement(
                "var " + builder + " = new java.lang.StringBuilder();"));
        for (AssignExpr candidate : loop.findAll(AssignExpr.class)) {
            if (!variable.equals(FixSafety.assignTargetName(candidate))) {
                continue;
            }
            Expression appended = FixSafety.appendedValue(candidate);
            if (appended == null) {
                continue;
            }
            candidate.replace(new MethodCallExpr(new NameExpr(builder), "append", new NodeList<>(appended.clone())));
        }
        FixSupport.insertAfter(FixSupport.loopStatement(loop), StaticJavaParser.parseStatement(
                variable + " = " + builder + ".toString();"));
        return "Конкатенация заменена на StringBuilder " + builder + ": строка не копируется на каждой итерации";
    }
}
