package io.blueprints.analysis.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.BigO;
import io.blueprints.analysis.Complexity;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Линейный поиск по коллекции внутри цикла по другой коллекции — «join без индекса».
 *
 * <pre>
 * for (Order order : orders) {
 *     for (Customer customer : customers) {          // O(n·m)
 *         if (order.customerId.equals(customer.id)) {
 *             ...                                     // сработает один раз благодаря break
 *             break;
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>Такая форма заменяется индексом {@code HashSet}/{@code HashMap} по ключу за один проход:
 * O(n·m) → O(n+m). Правило сознательно узкое: тело внутреннего цикла — ровно одна проверка равенства
 * с {@code break}, иначе однозначного эквивалентного переписывания нет (попарное сравнение и подсчёты
 * остаются ручными). Вложенные циклы по <em>одной</em> коллекции ловит `nested-loop-same-collection`.
 *
 * <p>Авто-исправление — {@code fixes/NestedLoopIndexFix} (подроль 2b), {@code Fix.kind = nested-loop-index}.
 */
public final class NestedLoopLinearSearchRule implements Rule {

    private static final String RULE = "nested-loop-linear-search";

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (ForEachStmt outer : cu.findAll(ForEachStmt.class)) {
                ForEachStmt inner = singleInnerLoop(outer);
                if (inner == null || sameIterable(outer, inner)) {
                    continue;
                }
                IfStmt lookup = singleEqualityWithBreak(inner);
                if (lookup == null) {
                    continue;
                }
                findings.add(finding(model, outer, inner));
            }
        });
        return findings;
    }

    private static Finding finding(ProjectModel model, ForEachStmt outer, ForEachStmt inner) {
        return Findings.of(model, RULE, Severity.HIGH,
                "Поиск совпадения перебором в цикле — O(n·m)",
                "Для каждого элемента внешнего цикла внутренний перебирает другую коллекцию целиком, "
                        + "хотя элементы те же: O(n·m) при размерах n и m. Построчный индекс "
                        + "(HashSet по ключу или HashMap) заменяет перебор на проверку за O(1), и весь метод "
                        + "становится O(n+m).",
                inner, List.of(outer, inner),
                new Complexity(BigO.of(2).toString(), BigO.of(1).toString()),
                new Fix("nested-loop-index",
                        "Построить HashSet по коллекции и заменить внутренний перебор проверкой вхождения",
                        null, false),
                "Убирает полный перебор второй коллекции на каждом шаге: O(n·m) → O(n+m)");
    }

    /** Внутренний цикл должен быть единственным оператором тела внешнего. */
    private static ForEachStmt singleInnerLoop(ForEachStmt outer) {
        List<Statement> statements = statements(outer.getBody());
        return statements.size() == 1 && statements.get(0) instanceof ForEachStmt inner ? inner : null;
    }

    private static boolean sameIterable(ForEachStmt outer, ForEachStmt inner) {
        return Ast.compact(outer.getIterable().toString()).equals(Ast.compact(inner.getIterable().toString()));
    }

    /**
     * Тело внутреннего цикла — ровно один {@code if (a.equals(b)) { ...; break; }} без else:
     * тогда «нашли первое совпадение» эквивалентно проверке вхождения в множество.
     */
    private static IfStmt singleEqualityWithBreak(ForEachStmt inner) {
        List<Statement> statements = statements(inner.getBody());
        if (statements.size() != 1 || !(statements.get(0) instanceof IfStmt ifStmt)) {
            return null;
        }
        if (ifStmt.getElseStmt().isPresent()) {
            return null;
        }
        if (!(ifStmt.getThenStmt() instanceof BlockStmt block) || block.getStatements().isEmpty()) {
            return null;
        }
        Statement last = block.getStatements().get(block.getStatements().size() - 1);
        if (!(last instanceof BreakStmt)) {
            return null;
        }
        String outerVariable = variableName(outer(inner));
        String innerVariable = variableName(inner);
        return isEquality(ifStmt.getCondition(), outerVariable, innerVariable) ? ifStmt : null;
    }

    private static ForEachStmt outer(Node inner) {
        Node parent = inner.getParentNode().orElse(null);
        if (parent instanceof BlockStmt block) {
            parent = block.getParentNode().orElse(null);
        }
        return parent instanceof ForEachStmt loop ? loop : null;
    }

    private static boolean isEquality(Expression condition, String outerVariable, String innerVariable) {
        if (condition instanceof MethodCallExpr call && "equals".equals(call.getNameAsString())) {
            if (call.getArguments().size() == 1) {
                return isName(call.getScope().orElse(null), outerVariable)
                        && isName(call.getArgument(0), innerVariable);
            }
            if (call.getArguments().size() == 2) {
                String scope = call.getScope().map(Node::toString).orElse("");
                return scope.endsWith("Objects")
                        && ((isName(call.getArgument(0), outerVariable) && isName(call.getArgument(1), innerVariable))
                        || (isName(call.getArgument(0), innerVariable) && isName(call.getArgument(1), outerVariable)));
            }
        }
        if (condition instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.EQUALS) {
            return (isName(binary.getLeft(), outerVariable) && isName(binary.getRight(), innerVariable))
                    || (isName(binary.getLeft(), innerVariable) && isName(binary.getRight(), outerVariable));
        }
        return false;
    }

    private static boolean isName(Expression expression, String name) {
        return expression instanceof NameExpr variable && variable.getNameAsString().equals(name);
    }

    private static String variableName(ForEachStmt loop) {
        if (loop == null || loop.getVariable().getVariables().size() != 1) {
            return "";
        }
        return loop.getVariable().getVariables().get(0).getNameAsString();
    }

    private static List<Statement> statements(Statement body) {
        return body instanceof BlockStmt block ? block.getStatements() : List.of(body);
    }
}
