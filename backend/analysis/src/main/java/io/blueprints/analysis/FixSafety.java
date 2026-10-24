package io.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Проверки безопасности авто-исправлений — единственный источник правды для двух мест:
 * правила выставляют по ним {@code Fix.autoApplicable} (это кнопка «Применить» в UI), а
 * {@link FixEngine} по ним же решает, применять ли правку.
 *
 * <p>Раньше проверки жили только в движке: анализ помечал находку применимой, кнопка была активна,
 * а сервер отвечал 422 — так и появился запрос роли 4 в {@code STATUS.md}. Теперь оба пути идут
 * через один {@link #explain(String, Node)}.
 */
public final class FixSafety {

    /** Правило без авто-исправления. */
    public static final String NOT_IMPLEMENTED = "для этого правила авто-исправление не реализовано";

    private static final Set<String> MUTATORS = Set.of(
            "add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "set", "sort",
            "replaceAll", "poll", "pollFirst", "pollLast", "push", "pop", "offer", "offerFirst", "offerLast");

    private FixSafety() {
    }

    /**
     * Причина, по которой исправление нельзя применить автоматически, или {@code null}, если можно.
     * Текст показывается и в UI (описание фикса), и в ответе 422 от {@code POST /fixes}.
     */
    public static String explain(String rule, Node anchor) {
        if (anchor == null) {
            return "у находки нет узла кода";
        }
        return switch (rule) {
            case "collection-lookup-in-loop" -> indexHoist(anchor);
            case "string-concat-in-loop" -> stringBuilder(anchor);
            case "magic-number" -> constant(anchor);
            case "linked-list-index-access" -> forEach(anchor);
            default -> NOT_IMPLEMENTED;
        };
    }

    public static boolean isApplicable(String rule, Node anchor) {
        return explain(rule, anchor) == null;
    }

    /** Бросает {@link FixNotApplicableException} с той же причиной, что видит пользователь в UI. */
    public static void require(String rule, Node anchor) {
        String reason = explain(rule, anchor);
        if (reason != null) {
            throw new FixNotApplicableException(reason);
        }
    }

    // ---------------------------------------------------------------- проверки по правилам

    private static String indexHoist(Node anchor) {
        if (!(anchor instanceof MethodCallExpr call)) {
            return "неожиданный узел находки";
        }
        Node loop = nearestLoop(call);
        if (loop == null) {
            return "вызов не внутри цикла";
        }
        String receiver = receiverName(call);
        if (receiver == null) {
            return "получатель вызова — не простая переменная";
        }
        String declared = declaredInside(loop, receiver);
        return declared != null ? declared : mutated(loop, receiver);
    }

    private static String stringBuilder(Node anchor) {
        if (!(anchor instanceof AssignExpr assign)) {
            return "неожиданный узел находки";
        }
        Node loop = nearestLoop(assign);
        if (loop == null) {
            return "присваивание не внутри цикла";
        }
        String variable = assignTargetName(assign);
        if (variable == null) {
            return "слева не простая переменная";
        }
        String declared = declaredInside(loop, variable);
        return declared != null ? declared : readsInside(loop, variable);
    }

    private static String constant(Node anchor) {
        if (!(anchor instanceof Statement statement)) {
            return "неожиданный узел находки";
        }
        if (statement.findAncestor(ClassOrInterfaceDeclaration.class).isEmpty()) {
            return "число вне класса: константу некуда вынести";
        }
        return null;
    }

    private static String forEach(Node anchor) {
        if (!(anchor instanceof MethodCallExpr call)) {
            return "неожиданный узел находки";
        }
        if (!(nearestLoop(call) instanceof ForStmt loop)) {
            return "обращение по индексу вне классического for";
        }
        String counter = Ast.counterVariable(loop);
        if (counter == null) {
            return "у цикла нет переменной-счётчика";
        }
        String receiver = receiverName(call);
        if (receiver == null) {
            return "получатель вызова — не простая переменная";
        }
        String mutated = mutated(loop, receiver);
        return mutated != null ? mutated : counterUsedOnlyForIndex(loop, counter, receiver);
    }

    // ---------------------------------------------------------------- общие проверки

    private static Node nearestLoop(Node node) {
        List<Node> loops = Ast.enclosingLoops(node);
        return loops.isEmpty() ? null : loops.get(0);
    }

    /** Имя переменной-получателя вызова; {@code null}, если получатель не простая переменная. */
    public static String receiverName(MethodCallExpr call) {
        return call.getScope().filter(NameExpr.class::isInstance).map(Node::toString).orElse(null);
    }

    /** Имя переменной слева от присваивания; {@code null}, если слева не имя и не поле. */
    public static String assignTargetName(AssignExpr assign) {
        if (assign.getTarget() instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (assign.getTarget() instanceof FieldAccessExpr field) {
            return field.getNameAsString();
        }
        return null;
    }

    /** Добавляемое значение для {@code s += x} и {@code s = s + x}; иначе {@code null}. */
    public static Expression appendedValue(AssignExpr assign) {
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

    private static String declaredInside(Node scope, String variable) {
        for (VariableDeclarator declarator : scope.findAll(VariableDeclarator.class)) {
            if (declarator.getNameAsString().equals(variable)) {
                return "переменная " + variable + " объявлена внутри цикла: вынести исправление наружу нельзя";
            }
        }
        return null;
    }

    private static String mutated(Node scope, String receiver) {
        for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
            if (MUTATORS.contains(call.getNameAsString()) && receiver.equals(receiverName(call))) {
                return "коллекция " + receiver + " меняется внутри цикла: индекс устарел бы";
            }
        }
        for (AssignExpr assign : scope.findAll(AssignExpr.class)) {
            if (assign.getTarget() instanceof NameExpr name && name.getNameAsString().equals(receiver)) {
                return "переменная " + receiver + " переприсваивается внутри цикла";
            }
        }
        return null;
    }

    /** Строку читают внутри цикла — StringBuilder изменил бы смысл. */
    private static String readsInside(Node loop, String variable) {
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
                return "строка " + variable + " читается внутри цикла: замена на StringBuilder изменила бы поведение";
            }
        }
        return null;
    }

    private static String counterUsedOnlyForIndex(Node loop, String counter, String receiver) {
        Statement body = Ast.loopBody(loop);
        if (body == null) {
            return null;
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
                return "переменная " + counter + " используется не только в get(): for-each изменил бы поведение";
            }
        }
        return null;
    }
}
