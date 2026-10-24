package io.blueprints.analysis.fixes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixSafety;
import io.blueprints.core.model.ProjectModel;

/** {@code list.contains(x)} в цикле → индекс {@code HashSet} перед циклом. */
final class IndexHoistFixer implements Fixer {

    @Override
    public String rule() {
        return "collection-lookup-in-loop";
    }

    @Override
    public String kind() {
        return "index-by-key";
    }

    @Override
    public String explain(Finding finding, ProjectModel model) {
        return FixSafety.explain(rule(), FixSupport.anchor(model, finding));
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        MethodCallExpr call = (MethodCallExpr) FixSupport.anchor(model, finding);
        Node loop = FixSupport.nearestLoop(call);
        String receiver = FixSupport.receiverOrFail(call);
        String index = FixSupport.uniqueName(call, "_index");
        Statement declaration = StaticJavaParser.parseStatement(
                "var " + index + " = new java.util.HashSet<>(" + receiver + ");");
        FixSupport.insertBefore(FixSupport.loopStatement(loop), declaration);
        for (MethodCallExpr candidate : loop.findAll(MethodCallExpr.class)) {
            if ("contains".equals(candidate.getNameAsString())
                    && receiver.equals(FixSafety.receiverName(candidate))) {
                candidate.setScope(new NameExpr(index));
            }
        }
        return "Построен HashSet " + index + ": contains больше не перебирает список";
    }
}
