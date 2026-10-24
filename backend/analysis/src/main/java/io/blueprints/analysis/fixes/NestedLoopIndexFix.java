package io.blueprints.analysis.fixes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.ProjectModel;

import java.util.List;

/**
 * Вложенный проход по коллекции ради поиска элемента → индекс {@code HashSet}.
 *
 * <p>Распознаётся ровно одна форма, в которой замена доказуемо сохраняет поведение:
 * <pre>
 * for (T a : xs) {                      →   var _index = new java.util.HashSet&lt;&gt;(ys);
 *     for (T b : ys) {                      for (T a : xs) {
 *         if (a.equals(b)) {                    if (_index.contains(a)) {
 *             ...тело...                            ...тело...
 *             break;                            }
 *         }
 *     }
 * }
 * </pre>
 *
 * <p>Почему это эквивалентно: {@code break} в конце гарантирует «нашли первый совпадающий элемент —
 * выполнили тело один раз», а {@code equals} симметричен, поэтому проверка вхождения в множество даёт
 * тот же результат. Всё, что не совпадает с формой (нет {@code break}, {@code b} используется в теле,
 * {@code else}, сравнение через {@code ==}, коллекция меняется в цикле), отклоняется — компиляция и
 * поведение важнее полноты.
 */
public final class NestedLoopIndexFix implements Fixer {

    @Override
    public String kind() {
        return "nested-loop-index";
    }

    @Override
    public String refusalReason(ProjectModel model, Finding finding) {
        try {
            structure(model, finding);
            return null;
        } catch (FixNotApplicableException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return FixAnchors.manual(finding);
        }
    }

    @Override
    public boolean supports(ProjectModel model, Finding finding) {
        return refusalReason(model, finding) == null;
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        Shape shape = structure(model, finding);
        String index = FixSupport.uniqueName(shape.inner(), "_index");
        FixSupport.insertBefore(FixSupport.loopStatement(shape.outer()),
                StaticJavaParser.parseStatement(
                        "var " + index + " = new java.util.HashSet<>(" + shape.iterable() + ");"));

        // Меняем исходный if на месте, без clone: LexicalPreservingPrinter печатает копию по старым токенам.
        IfStmt lookup = shape.ifStmt();
        BlockStmt body = lookup.getThenStmt().asBlockStmt();
        body.getStatements().removeLast();
        lookup.setCondition(new MethodCallExpr(
                new NameExpr(index), "contains", NodeList.nodeList(new NameExpr(shape.outerVariable()))));
        lookup.remove();
        shape.inner().replace(lookup);
        return "Внутренний проход заменён проверкой вхождения в HashSet " + index
                + ": было O(n²), стало O(n)";
    }

    // ---------------------------------------------------------------- распознавание формы

    private record Shape(ForEachStmt outer, ForEachStmt inner, IfStmt ifStmt, String outerVariable,
                         String iterable) {
    }

    private static Shape structure(ProjectModel model, Finding finding) {
        Node anchor = FixAnchors.anchor(model, finding);
        ForEachStmt outer = anchor instanceof ForEachStmt loop ? loop : null;
        if (outer == null) {
            throw new FixNotApplicableException("Внешний цикл не найден: исправление вручную");
        }
        ForEachStmt inner = innerLoop(outer);
        IfStmt ifStmt = onlyIf(inner);
        String outerVariable = variableName(outer);
        String innerVariable = variableName(inner);
        requireEqualityCondition(ifStmt, outerVariable, innerVariable);
        String iterable = iterableName(inner);
        FixSupport.requireNotMutated(outer, iterable);
        if (usesBodyVariable(ifStmt, innerVariable)) {
            throw new FixNotApplicableException("Во внутреннем теле используется элемент " + innerVariable
                    + ": заменить цикл индексом нельзя, исправление вручную");
        }
        return new Shape(outer, inner, ifStmt, outerVariable, iterable);
    }

    private static ForEachStmt innerLoop(ForEachStmt outer) {
        Statement body = outer.getBody();
        List<Statement> statements = body instanceof BlockStmt block ? block.getStatements() : List.of(body);
        if (statements.size() != 1 || !(statements.get(0) instanceof ForEachStmt inner)) {
            throw new FixNotApplicableException("В теле внешнего цикла больше одного оператора: "
                    + "перенос индекса изменил бы порядок, исправление вручную");
        }
        return inner;
    }

    private static IfStmt onlyIf(ForEachStmt inner) {
        Statement body = inner.getBody();
        List<Statement> statements = body instanceof BlockStmt block ? block.getStatements() : List.of(body);
        if (statements.size() != 1 || !(statements.get(0) instanceof IfStmt ifStmt)) {
            throw new FixNotApplicableException("Внутренний цикл — не единственная проверка равенства: "
                    + "исправление вручную");
        }
        if (ifStmt.getElseStmt().isPresent()) {
            throw new FixNotApplicableException("У проверки есть else: у HashSet нет понятия «первый элемент»");
        }
        if (!(ifStmt.getThenStmt() instanceof BlockStmt block) || block.getStatements().isEmpty()) {
            throw new FixNotApplicableException("Тело проверки пустое: исправлять нечего");
        }
        Statement last = block.getStatements().get(block.getStatements().size() - 1);
        if (!(last instanceof BreakStmt)) {
            throw new FixNotApplicableException("В конце проверки нет break: цикл может сработать несколько раз");
        }
        return ifStmt;
    }

    private static String variableName(ForEachStmt loop) {
        VariableDeclarationExpr variable = loop.getVariable();
        if (variable.getVariables().size() != 1) {
            throw new FixNotApplicableException("Неожиданная переменная цикла: исправление вручную");
        }
        return variable.getVariables().get(0).getNameAsString();
    }

    private static void requireEqualityCondition(IfStmt ifStmt, String outerVariable, String innerVariable) {
        boolean matches = equalsPair(ifStmt.getCondition(), outerVariable, innerVariable)
                || equalsPair(ifStmt.getCondition(), innerVariable, outerVariable)
                || objectsEquals(ifStmt.getCondition(), outerVariable, innerVariable);
        if (!matches) {
            throw new FixNotApplicableException("Условие — не сравнение элементов через equals: "
                    + "индекс по значению не эквивалентен, исправление вручную");
        }
    }

    private static boolean equalsPair(Expression condition, String receiver, String argument) {
        return condition instanceof MethodCallExpr call
                && "equals".equals(call.getNameAsString())
                && call.getArguments().size() == 1
                && call.getScope().filter(NameExpr.class::isInstance)
                        .map(scope -> scope.asNameExpr().getNameAsString().equals(receiver)).orElse(false)
                && call.getArgument(0) instanceof NameExpr argumentExpr
                && argumentExpr.getNameAsString().equals(argument);
    }

    private static boolean objectsEquals(Expression condition, String outerVariable, String innerVariable) {
        if (!(condition instanceof MethodCallExpr call) || !"equals".equals(call.getNameAsString())
                || call.getArguments().size() != 2) {
            return false;
        }
        String scope = call.getScope().map(Node::toString).orElse("");
        if (!scope.endsWith("Objects")) {
            return false;
        }
        return isName(call.getArgument(0), outerVariable) && isName(call.getArgument(1), innerVariable)
                || isName(call.getArgument(0), innerVariable) && isName(call.getArgument(1), outerVariable);
    }

    private static boolean isName(Expression expression, String name) {
        return expression instanceof NameExpr variable && variable.getNameAsString().equals(name);
    }

    /** {@code b} не должен использоваться в теле: в индекс попадает значение, а не элемент. */
    private static boolean usesBodyVariable(IfStmt ifStmt, String innerVariable) {
        return ifStmt.getThenStmt().findAll(NameExpr.class).stream()
                .anyMatch(name -> name.getNameAsString().equals(innerVariable));
    }

    private static String iterableName(ForEachStmt loop) {
        Expression iterable = loop.getIterable();
        if (iterable instanceof NameExpr name) {
            return name.getNameAsString();
        }
        throw new FixNotApplicableException("Коллекция внутреннего цикла — не переменная: "
                + "индекс построить нельзя, исправление вручную");
    }
}
