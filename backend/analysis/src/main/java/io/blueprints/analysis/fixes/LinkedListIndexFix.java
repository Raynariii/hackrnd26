package io.blueprints.analysis.fixes;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.type.VarType;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.ProjectModel;

/**
 * {@code list.get(i)} по {@code LinkedList} в классическом {@code for} → обход for-each.
 *
 * <p>Отказываемся, если цикл идёт не от 0 до {@code size()} с шагом 1, счётчик используется не только для
 * {@code get(i)} или коллекция меняется в цикле: тогда обход за один проход изменил бы поведение программы.
 */
public final class LinkedListIndexFix implements Fixer {

    @Override
    public String kind() {
        return "iterator";
    }

    @Override
    public String refusalReason(ProjectModel model, Finding finding) {
        try {
            Node anchor = FixAnchors.anchor(model, finding);
            if (!(anchor instanceof MethodCallExpr call)) {
                return FixAnchors.manual(finding);
            }
            Node loopNode = FixSupport.nearestLoop(call);
            if (!(loopNode instanceof ForStmt loop)) {
                throw new FixNotApplicableException("Обращение по индексу вне классического for: меняйте вручную");
            }
            String counter = Ast.counterVariable(loop);
            String receiver = FixSupport.receiverName(call);
            if (counter == null) {
                throw new FixNotApplicableException("У цикла нет переменной-счётчика");
            }
            FixSupport.requireFullTraversal(loop, counter, receiver);
            FixSupport.requireNotMutated(loop, receiver);
            FixSupport.requireCounterUsedOnlyForIndex(loop, counter, receiver);
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
        Node loopNode = FixSupport.nearestLoop(call);
        if (!(loopNode instanceof ForStmt loop)) {
            throw new FixNotApplicableException("Обращение по индексу вне классического for: меняйте вручную");
        }
        String counter = Ast.counterVariable(loop);
        String receiver = FixSupport.receiverName(call);
        if (counter == null) {
            throw new FixNotApplicableException("У цикла нет переменной-счётчика");
        }
        FixSupport.requireFullTraversal(loop, counter, receiver);
        FixSupport.requireNotMutated(loop, receiver);
        FixSupport.requireCounterUsedOnlyForIndex(loop, counter, receiver);
        String item = FixSupport.uniqueName(call, "_item");
        for (MethodCallExpr candidate : loop.getBody().findAll(MethodCallExpr.class)) {
            if ("get".equals(candidate.getNameAsString()) && receiver.equals(FixSupport.receiverName(candidate))) {
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
