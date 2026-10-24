package io.blueprints.analysis.fixes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.ProjectModel;

/**
 * {@code s += item} в цикле → {@code StringBuilder}: строка не копируется на каждой итерации.
 *
 * <p>Отказываемся, если переменная объявлена внутри цикла или читается в нём (кроме самих
 * конкатенаций): иначе замена на {@code StringBuilder} изменила бы поведение.
 */
public final class StringConcatFix implements Fixer {

    @Override
    public String kind() {
        return "string-builder";
    }

    @Override
    public String refusalReason(ProjectModel model, Finding finding) {
        try {
            Node anchor = FixAnchors.anchor(model, finding);
            if (!(anchor instanceof AssignExpr assign)) {
                return FixAnchors.manual(finding);
            }
            Node loop = FixSupport.nearestLoop(assign);
            String variable = FixSupport.assignTargetName(assign);
            FixSupport.requireNotDeclaredInside(loop, variable);
            FixSupport.requireNoReadsInside(loop, variable);
            return null;
        } catch (FixNotApplicableException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return FixAnchors.manual(finding);
        }
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        AssignExpr assign = (AssignExpr) FixAnchors.anchor(model, finding);
        Node loop = FixSupport.nearestLoop(assign);
        String variable = FixSupport.assignTargetName(assign);
        FixSupport.requireNotDeclaredInside(loop, variable);
        FixSupport.requireNoReadsInside(loop, variable);
        String builder = FixSupport.uniqueName(assign, "_sb");
        FixSupport.insertBefore(FixSupport.loopStatement(loop), StaticJavaParser.parseStatement(
                "var " + builder + " = new java.lang.StringBuilder();"));
        for (AssignExpr candidate : loop.findAll(AssignExpr.class)) {
            if (!variable.equals(FixSupport.assignTargetName(candidate))) {
                continue;
            }
            Expression appended = FixSupport.appendedValue(candidate);
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
