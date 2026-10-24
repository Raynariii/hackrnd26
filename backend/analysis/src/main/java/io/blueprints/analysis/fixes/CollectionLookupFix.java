package io.blueprints.analysis.fixes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.ProjectModel;

/**
 * {@code list.contains(x)} / {@code indexOf} / {@code remove(x)} внутри цикла → индекс {@code HashSet}.
 *
 * <p>Правило {@code collection-lookup-in-loop} описывает исправление, а этот класс решает, можно ли
 * его применить: коллекция не должна объявляться или меняться внутри цикла — иначе построенный
 * индекс устарел бы и поведение изменилось.
 */
public final class CollectionLookupFix implements Fixer {

    @Override
    public String kind() {
        return "index-by-key";
    }

    @Override
    public String refusalReason(ProjectModel model, Finding finding) {
        try {
            Node anchor = FixAnchors.anchor(model, finding);
            if (!(anchor instanceof MethodCallExpr call)) {
                return FixAnchors.manual(finding);
            }
            Node loop = FixSupport.nearestLoop(call);
            String receiver = FixSupport.receiverName(call);
            FixSupport.requireNotDeclaredInside(loop, receiver);
            FixSupport.requireNotMutated(loop, receiver);
            return null;
        } catch (FixNotApplicableException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return FixAnchors.manual(finding);
        }
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        MethodCallExpr call = (MethodCallExpr) FixAnchors.anchor(model, finding);
        Node loop = FixSupport.nearestLoop(call);
        String receiver = FixSupport.receiverName(call);
        FixSupport.requireNotDeclaredInside(loop, receiver);
        FixSupport.requireNotMutated(loop, receiver);
        String index = FixSupport.uniqueName(call, "_index");
        Statement declaration = StaticJavaParser.parseStatement(
                "var " + index + " = new java.util.HashSet<>(" + receiver + ");");
        FixSupport.insertBefore(FixSupport.loopStatement(loop), declaration);
        for (MethodCallExpr candidate : loop.findAll(MethodCallExpr.class)) {
            if (candidate.getNameAsString().equals("contains") && receiver.equals(FixSupport.receiverName(candidate))) {
                candidate.setScope(new NameExpr(index));
            }
        }
        return "Построен HashSet " + index + ": contains больше не перебирает список";
    }
}
