package io.blueprints.analysis.fixes;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.type.VarType;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.analysis.FixSafety;
import io.blueprints.core.model.ProjectModel;

/** {@code for (int i = 0; i < list.size(); i++) list.get(i)} → обход {@code for-each}. */
final class ForEachFixer implements Fixer {

    @Override
    public String rule() {
        return "linked-list-index-access";
    }

    @Override
    public String kind() {
        return "iterator";
    }

    @Override
    public String explain(Finding finding, ProjectModel model) {
        return FixSafety.explain(rule(), FixSupport.anchor(model, finding));
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        MethodCallExpr call = (MethodCallExpr) FixSupport.anchor(model, finding);
        if (!(FixSupport.nearestLoop(call) instanceof ForStmt loop)) {
            throw new FixNotApplicableException("Обращение по индексу вне классического for: меняйте вручную");
        }
        String counter = Ast.counterVariable(loop);
        if (counter == null) {
            throw new FixNotApplicableException("У цикла нет переменной-счётчика");
        }
        String receiver = FixSupport.receiverOrFail(call);
        String item = FixSupport.uniqueName(call, "_item");
        for (MethodCallExpr candidate : loop.getBody().findAll(MethodCallExpr.class)) {
            if ("get".equals(candidate.getNameAsString()) && receiver.equals(FixSafety.receiverName(candidate))) {
                candidate.replace(new NameExpr(item));
            }
        }
        ForEachStmt each = new ForEachStmt(
                new VariableDeclarationExpr(new VarType(), item),
                new NameExpr(receiver),
                loop.getBody());
        loop.replace(each);
        return "Индексация заменена на for-each: LinkedList обходится за один проход";
    }
}
