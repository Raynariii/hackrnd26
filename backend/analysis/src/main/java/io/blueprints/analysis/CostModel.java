package io.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.Locale;
import java.util.Set;

/**
 * Таблица стоимости операций над коллекциями и строками + вывод объявленного типа переменной.
 *
 * <p>Основа оценки Big O из PLAN.md §4: сложность метода складывается из глубины
 * вложенности циклов и стоимости операций внутри них. {@code ArrayList.contains} — O(n),
 * {@code HashSet.contains} — O(1), {@code TreeMap.get} — O(log n), сортировка — O(n log n),
 * терминальная операция Stream API — один проход O(n), а лямбда в {@code filter}/{@code map}/
 * {@code anyMatch} выполняется на каждом элементе (её стоимость умножается на n).
 *
 * <p>Типы берутся из AST (объявленный тип переменной, параметра или поля), без
 * {@code SymbolSolver}: анализ остаётся чистой функцией и не требует classpath —
 * тот же принцип «с откатом на имена», что и в ядре.
 */
public final class CostModel {

    /** Коллекции с линейным поиском/удалением по значению. */
    private static final Set<String> LINEAR_TYPES = Set.of(
            "list", "arraylist", "linkedlist", "vector", "stack", "collection", "iterable",
            "abstractlist", "copyonwritearraylist", "queue", "deque", "arraydeque", "priorityqueue");

    /** Хеш-коллекции: поиск в среднем O(1). */
    private static final Set<String> HASH_TYPES = Set.of(
            "hashset", "linkedhashset", "hashmap", "linkedhashmap", "set", "map",
            "concurrenthashmap", "hashtable", "identityhashmap", "weakhashmap", "enumset", "enummap");

    /** Деревья: поиск O(log n). */
    private static final Set<String> TREE_TYPES = Set.of(
            "treeset", "treemap", "sortedset", "sortedmap", "navigableset", "navigablemap",
            "concurrentskiplistmap", "concurrentskiplistset");

    /** Имена вызовов, которые на списке стоят O(n) на элемент. */
    private static final Set<String> LINEAR_LOOKUPS = Set.of(
            "contains", "indexOf", "lastIndexOf", "containsAll", "removeAll", "retainAll", "remove", "removeIf");

    /**
     * Терминальные операции Stream API: один проход по источнику, в худшем случае O(n).
     * Промежуточные операции ({@code filter}, {@code map}) ленивы и сами стоят O(1) — их стоимость
     * проявляется через лямбду, которая выполняется на каждом элементе (см. {@link #iteratesPerElement}).
     */
    private static final Set<String> STREAM_TERMINALS = Set.of(
            "collect", "toList", "toSet", "toArray", "toMap", "forEach", "forEachOrdered",
            "count", "sum", "average", "reduce",
            "anyMatch", "allMatch", "noneMatch", "findFirst", "findAny");

    /** Операции, которые выполняют переданную лямбду на каждом элементе источника. */
    private static final Set<String> PER_ELEMENT = Set.of(
            "filter", "map", "mapToInt", "mapToLong", "mapToDouble", "flatMap",
            "flatMapToInt", "flatMapToLong", "flatMapToDouble", "peek",
            "forEach", "forEachOrdered", "removeIf", "replaceAll", "sort",
            "anyMatch", "allMatch", "noneMatch", "reduce", "collect",
            "groupingBy", "partitioningBy", "mapping", "flatMapping", "toMap",
            "averagingInt", "averagingLong", "averagingDouble",
            "summingInt", "summingLong", "summingDouble", "minBy", "maxBy",
            "comparing", "thenComparing");

    /** Типы, у которых {@code map/filter} вызывают лямбду не более одного раза. */
    private static final Set<String> OPTIONAL_TYPES = Set.of(
            "optional", "optionalint", "optionallong", "optionaldouble");

    /** Получатели, у которых {@code min/max} — операция над числами, а не проход по коллекции. */
    private static final Set<String> MATH_RECEIVERS = Set.of(
            "math", "strictmath", "integer", "long", "double", "float", "short", "byte", "character");

    /** Стоимость вызова метода, если тип получателя известен. */
    public BigO cost(MethodCallExpr call) {
        String name = call.getNameAsString();
        String type = typeKey(receiverType(call));
        if (STREAM_TERMINALS.contains(name)) {
            return BigO.of(1);
        }
        return switch (name) {
            case "contains", "indexOf", "lastIndexOf", "containsAll", "removeAll", "retainAll",
                    "remove", "removeIf" -> lookupCost(type);
            case "get", "getFirst", "getLast", "elementAt" -> getCost(type);
            case "containsValue" -> HASH_TYPES.contains(type) || TREE_TYPES.contains(type) ? BigO.of(1) : BigO.constant();
            case "sort", "sorted" -> BigO.nLogN();
            case "min", "max" -> isMathCall(call) ? BigO.constant() : BigO.of(1);
            default -> BigO.constant();
        };
    }

    /**
     * Выполняет ли вызов переданную лямбду на каждом элементе источника: Stream API,
     * {@code Iterable.forEach}, {@code Collection.removeIf/replaceAll}, {@code List.sort}.
     *
     * <p>Для {@code Optional.map/filter} лямбда вызывается не более одного раза — там множитель n
     * не появляется, иначе {@code optional.map(x -> list.contains(x))} ошибочно стал бы O(n²).
     */
    public boolean iteratesPerElement(MethodCallExpr call) {
        if (!PER_ELEMENT.contains(call.getNameAsString())) {
            return false;
        }
        return !OPTIONAL_TYPES.contains(typeKey(receiverType(call)));
    }

    /** {@code Math.max(a, b)} — O(1); {@code stream().max(...)} и {@code Collections.max(list)} — проход. */
    private static boolean isMathCall(MethodCallExpr call) {
        return call.getScope()
                .map(Node::toString)
                .map(scope -> scope.toLowerCase(Locale.ROOT))
                .filter(MATH_RECEIVERS::contains)
                .isPresent();
    }

    /**
     * Линейный поиск по коллекции: {@code list.contains(x)} внутри цикла — источник O(n²).
     * Возвращает {@code false}, если тип получателя неизвестен или не коллекция:
     * лучше пропустить находку, чем показать ложную.
     */
    public boolean isLinearLookup(MethodCallExpr call) {
        return LINEAR_LOOKUPS.contains(call.getNameAsString()) && LINEAR_TYPES.contains(typeKey(receiverType(call)));
    }

    /** Доступ по индексу к {@code LinkedList}: {@code list.get(i)} стоит O(n). */
    public boolean isLinearIndexAccess(MethodCallExpr call) {
        return "get".equals(call.getNameAsString()) && "linkedlist".equals(typeKey(receiverType(call)));
    }

    /** Объявленный тип получателя вызова ({@code orders.contains(x)} → {@code List}), либо null. */
    public String receiverType(MethodCallExpr call) {
        String variable = call.getScope().map(CostModel::receiverName).orElse(null);
        return variable == null ? null : declaredType(call, variable);
    }

    private static BigO lookupCost(String type) {
        if (LINEAR_TYPES.contains(type)) {
            return BigO.of(1);
        }
        if (TREE_TYPES.contains(type)) {
            return BigO.log();
        }
        return BigO.constant();
    }

    private static BigO getCost(String type) {
        if ("linkedlist".equals(type)) {
            return BigO.of(1);
        }
        if (TREE_TYPES.contains(type)) {
            return BigO.log();
        }
        return BigO.constant();
    }

    private static String typeKey(String type) {
        return type == null ? "" : type.toLowerCase(Locale.ROOT);
    }

    private static String receiverName(Expression scope) {
        if (scope instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr field) {
            return field.getNameAsString();
        }
        if (scope instanceof ThisExpr) {
            return "this";
        }
        return null;
    }

    /**
     * Объявленный тип переменной {@code variable} в точке {@code context}: параметр или локальная
     * переменная метода, иначе поле класса. Неизвестный тип → {@code null}.
     */
    public static String declaredType(Node context, String variable) {
        if (variable == null || variable.isEmpty() || "this".equals(variable)) {
            return null;
        }
        MethodDeclaration method = context.findAncestor(MethodDeclaration.class).orElse(null);
        if (method != null) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getNameAsString().equals(variable)) {
                    return simpleName(parameter.getType().asString());
                }
            }
            for (VariableDeclarator declarator : method.findAll(VariableDeclarator.class)) {
                if (declarator.getNameAsString().equals(variable)) {
                    return simpleName(declarator.getType().asString());
                }
            }
        }
        ClassOrInterfaceDeclaration type = context.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (type != null) {
            for (FieldDeclaration field : type.getFields()) {
                for (VariableDeclarator declarator : field.getVariables()) {
                    if (declarator.getNameAsString().equals(variable)) {
                        return simpleName(declarator.getType().asString());
                    }
                }
            }
        }
        return null;
    }

    private static String simpleName(String type) {
        String name = type.replace("[]", "").replace("...", "").trim();
        int generic = name.indexOf('<');
        if (generic >= 0) {
            name = name.substring(0, generic);
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
