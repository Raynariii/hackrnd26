package io.blueprints.analysis.fixes;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.FixNotApplicableException;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Общие проверки и правки AST для детерминированных исправлений: цикл-контейнер, получатель вызова,
 * вставка операторов с сохранением места, уникальные имена. Здесь же — отказы на случаи, когда
 * правка изменила бы поведение; их тексты видит пользователь.
 */
final class FixSupport {

    private static final Set<String> MUTATORS = Set.of(
            "add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "set", "sort",
            "replaceAll", "poll", "pollFirst", "pollLast", "push", "pop", "offer", "offerFirst", "offerLast");

    private FixSupport() {
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

    static String receiverName(MethodCallExpr call) {
        Expression scope = call.getScope().orElse(null);
        if (scope instanceof NameExpr name) {
            return name.getNameAsString();
        }
        throw new FixNotApplicableException("Получатель вызова — не простая переменная: исправление вручную");
    }

    static String assignTargetName(AssignExpr assign) {
        if (assign.getTarget() instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (assign.getTarget() instanceof FieldAccessExpr field) {
            return field.getNameAsString();
        }
        throw new FixNotApplicableException("Слева не переменная: исправление вручную");
    }

    static Expression appendedValue(AssignExpr assign) {
        if (assign.getOperator() == AssignExpr.Operator.PLUS) {
            return assign.getValue();
        }
        if (assign.getOperator() == AssignExpr.Operator.ASSIGN
                && assign.getValue() instanceof BinaryExpr binary
                && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            return binary.getRight();
        }
        return null;
    }

    static void requireNotDeclaredInside(Node scope, String variable) {
        for (VariableDeclarator declarator : scope.findAll(VariableDeclarator.class)) {
            if (declarator.getNameAsString().equals(variable)) {
                throw new FixNotApplicableException("Переменная " + variable
                        + " объявлена внутри цикла: вынести исправление наружу нельзя");
            }
        }
    }

    static void requireNotMutated(Node scope, String receiver) {
        for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
            if (MUTATORS.contains(call.getNameAsString())
                    && call.getScope().filter(NameExpr.class::isInstance)
                            .map(Node::toString)
                            .filter(receiver::equals)
                            .isPresent()) {
                throw new FixNotApplicableException("Коллекция " + receiver
                        + " меняется внутри цикла: индекс устарел бы, исправление вручную");
            }
        }
        for (AssignExpr assign : scope.findAll(AssignExpr.class)) {
            if (assign.getTarget() instanceof NameExpr name && name.getNameAsString().equals(receiver)) {
                throw new FixNotApplicableException("Переменная " + receiver
                        + " переприсваивается внутри цикла: исправление вручную");
            }
        }
    }

    /** Строку читают внутри цикла — StringBuilder изменил бы смысл; такое не правим автоматически. */
    static void requireNoReadsInside(Node loop, String variable) {
        Set<Node> writes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AssignExpr assign : loop.findAll(AssignExpr.class)) {
            if (!variable.equals(assignTargetName(assign)) || appendedValue(assign) == null) {
                continue;
            }
            writes.add(assign.getTarget());
            if (assign.getValue() instanceof BinaryExpr binary && binary.getLeft() instanceof NameExpr left) {
                writes.add(left);
            }
        }
        for (NameExpr name : loop.findAll(NameExpr.class)) {
            if (name.getNameAsString().equals(variable) && !writes.contains(name)) {
                throw new FixNotApplicableException("Строка " + variable
                        + " читается внутри цикла: замена на StringBuilder изменила бы поведение");
            }
        }
    }

    /**
     * Цикл должен обходить коллекцию целиком: счётчик от 0, условие {@code i < receiver.size()}, шаг {@code i++}.
     * Иначе for-each обошёл бы другие элементы ({@code for (int i = from; …)} — хвост списка, {@code i += 2} — каждый
     * второй) и изменил бы поведение.
     */
    static void requireFullTraversal(ForStmt loop, String counter, String receiver) {
        String why = "Цикл идёт не по всем элементам " + receiver
                + " (от 0 до size() с шагом 1): for-each изменил бы поведение — меняйте вручную";
        boolean startsAtZero = loop.getInitialization().size() == 1
                && loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration
                && declaration.getVariables().size() == 1
                && declaration.getVariable(0).getNameAsString().equals(counter)
                && declaration.getVariable(0).getInitializer()
                        .filter(init -> init instanceof IntegerLiteralExpr literal && asLong(literal) == 0)
                        .isPresent();
        boolean upToSize = loop.getCompare()
                .filter(compare -> compare instanceof BinaryExpr binary
                        && binary.getOperator() == BinaryExpr.Operator.LESS
                        && binary.getLeft() instanceof NameExpr left && left.getNameAsString().equals(counter)
                        && binary.getRight() instanceof MethodCallExpr size
                        && "size".equals(size.getNameAsString()) && size.getArguments().isEmpty()
                        && receiver.equals(receiverName(size)))
                .isPresent();
        boolean stepOne = loop.getUpdate().size() == 1 && isIncrementByOne(loop.getUpdate().get(0), counter);
        if (!startsAtZero || !upToSize || !stepOne) {
            throw new FixNotApplicableException(why);
        }
    }

    private static boolean isIncrementByOne(Expression update, String counter) {
        if (update instanceof UnaryExpr unary) {
            return (unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                    || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)
                    && unary.getExpression() instanceof NameExpr name && name.getNameAsString().equals(counter);
        }
        return update instanceof AssignExpr assign
                && assign.getOperator() == AssignExpr.Operator.PLUS
                && assign.getTarget() instanceof NameExpr name && name.getNameAsString().equals(counter)
                && assign.getValue() instanceof IntegerLiteralExpr one && asLong(one) == 1;
    }

    /** Счётчик цикла должен использоваться только для {@code get(i)}, иначе for-each изменит смысл. */
    static void requireCounterUsedOnlyForIndex(Node loop, String counter, String receiver) {
        Statement body = Ast.loopBody(loop);
        if (body == null) {
            return;
        }
        for (NameExpr name : body.findAll(NameExpr.class)) {
            if (!name.getNameAsString().equals(counter)) {
                continue;
            }
            boolean indexOfGet = name.getParentNode().stream()
                    .map(parent -> parent instanceof MethodCallExpr call
                            && "get".equals(call.getNameAsString())
                            && receiver.equals(receiverName(call)))
                    .findFirst()
                    .orElse(false);
            if (!indexOfGet) {
                throw new FixNotApplicableException("Переменная " + counter
                        + " используется не только в get(): переход на for-each изменил бы поведение");
            }
        }
    }

    static void insertBefore(Statement target, Statement statement) {
        Node parent = target.getParentNode().orElseThrow(() -> new FixNotApplicableException("Не найден блок для вставки"));
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(target);
            block.getStatements().add(index, statement);
            return;
        }
        BlockStmt wrapper = new BlockStmt();
        target.replace(wrapper);
        wrapper.addStatement(statement);
        wrapper.addStatement(target);
    }

    static void insertAfter(Statement target, Statement statement) {
        Node parent = target.getParentNode().orElseThrow(() -> new FixNotApplicableException("Не найден блок для вставки"));
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(target);
            block.getStatements().add(index + 1, statement);
            return;
        }
        BlockStmt wrapper = new BlockStmt();
        target.replace(wrapper);
        wrapper.addStatement(target);
        wrapper.addStatement(statement);
    }

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

    static long asLong(IntegerLiteralExpr literal) {
        try {
            return literal.asNumber().longValue();
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
