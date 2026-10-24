package io.blueprints.analysis.fixes;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixSafety;
import io.blueprints.core.model.ProjectModel;

import java.util.HashSet;
import java.util.Set;

/** Общие операции фиксеров: поиск узла находки, вставка операторов, уникальные имена. */
final class FixSupport {

    private FixSupport() {
    }

    /** AST-узел, на который указывает первая ссылка находки ({@code nodeIds} = {@code "g" + astId}). */
    static Node anchor(ProjectModel model, Finding finding) {
        if (finding.nodeIds().isEmpty()) {
            throw new FixNotApplicableException("У находки нет привязки к узлу кода");
        }
        String nodeId = finding.nodeIds().get(0);
        return model.nodeById(nodeId.substring(1))
                .orElseThrow(() -> new FixNotApplicableException("Узел " + nodeId + " не найден в текущей версии"));
    }

    static Node nearestLoop(Node node) {
        return Ast.enclosingLoops(node).stream().findFirst()
                .orElseThrow(() -> new FixNotApplicableException("Узел не внутри цикла"));
    }

    static Statement loopStatement(Node loop) {
        if (loop instanceof Statement statement) {
            return statement;
        }
        throw new FixNotApplicableException("Цикл не является оператором — исправление вручную");
    }

    static String receiverOrFail(MethodCallExpr call) {
        String receiver = FixSafety.receiverName(call);
        if (receiver == null) {
            throw new FixNotApplicableException("Получатель вызова — не простая переменная: исправление вручную");
        }
        return receiver;
    }

    static void insertBefore(Statement target, Statement statement) {
        Node parent = target.getParentNode()
                .orElseThrow(() -> new FixNotApplicableException("Не найден блок для вставки"));
        if (parent instanceof BlockStmt block) {
            block.getStatements().add(block.getStatements().indexOf(target), statement);
            return;
        }
        BlockStmt wrapper = new BlockStmt();
        target.replace(wrapper);
        wrapper.addStatement(statement);
        wrapper.addStatement(target);
    }

    static void insertAfter(Statement target, Statement statement) {
        Node parent = target.getParentNode()
                .orElseThrow(() -> new FixNotApplicableException("Не найден блок для вставки"));
        if (parent instanceof BlockStmt block) {
            block.getStatements().add(block.getStatements().indexOf(target) + 1, statement);
            return;
        }
        BlockStmt wrapper = new BlockStmt();
        target.replace(wrapper);
        wrapper.addStatement(target);
        wrapper.addStatement(statement);
    }

    /** Имя, которого нет среди параметров, локальных переменных и полей охватывающего кода. */
    static String uniqueName(Node context, String base) {
        Set<String> used = new HashSet<>();
        MethodDeclaration method = context.findAncestor(MethodDeclaration.class).orElse(null);
        if (method != null) {
            method.getParameters().forEach(parameter -> used.add(parameter.getNameAsString()));
            method.findAll(VariableDeclarator.class).forEach(declarator -> used.add(declarator.getNameAsString()));
        }
        context.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(type -> type.getFields()
                .forEach(field -> field.getVariables().forEach(declarator -> used.add(declarator.getNameAsString()))));
        if (!used.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }
}
