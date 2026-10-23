package io.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.blueprints.core.model.ProjectModel;

/**
 * Метрики метода по PLAN.md §4: цикломатическая сложность, максимальная вложенность блоков
 * и оценка Big O из AST.
 *
 * <p>Big O выводится из двух вещей: глубины вложенности циклов (каждый цикл — множитель n)
 * и стоимости операций внутри них по таблице {@link CostModel}. Оценка детерминированная
 * и объяснимая — её можно показать жюри рядом с находкой.
 *
 * <p>Ограничение: рекурсия и {@code while} с неизвестным числом итераций считаются как O(n)
 * за уровень вложенности; это отражено в {@code docs/scope.md}-описании анализа.
 */
public final class MetricsCalculator {

    private final CostModel costModel;

    public MetricsCalculator() {
        this(new CostModel());
    }

    public MetricsCalculator(CostModel costModel) {
        this.costModel = costModel;
    }

    public MethodMetrics forMethod(ProjectModel model, String file, MethodDeclaration method) {
        int lines = method.getRange()
                .map(range -> range.end.line - range.begin.line + 1)
                .orElse(0);
        return new MethodMetrics(
                Ast.nodeId(model, method),
                method.getNameAsString(),
                file,
                lines,
                cyclomatic(method),
                maxNesting(method),
                complexity(method).toString());
    }

    /** Цикломатическая сложность: 1 + точки ветвления (условия, циклы, catch, case, && и ||). */
    public static int cyclomatic(MethodDeclaration method) {
        int decisions = method.findAll(IfStmt.class).size()
                + method.findAll(ForStmt.class).size()
                + method.findAll(ForEachStmt.class).size()
                + method.findAll(WhileStmt.class).size()
                + method.findAll(DoStmt.class).size()
                + method.findAll(CatchClause.class).size()
                + method.findAll(ConditionalExpr.class).size();
        for (BinaryExpr binary : method.findAll(BinaryExpr.class)) {
            if (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR) {
                decisions++;
            }
        }
        for (SwitchEntry entry : method.findAll(SwitchEntry.class)) {
            if (!entry.getLabels().isEmpty()) {
                decisions++;
            }
        }
        return 1 + decisions;
    }

    /** Максимальная глубина вложенности блоков (цикл / if / switch / try / catch). */
    public static int maxNesting(MethodDeclaration method) {
        return method.getBody().map(body -> nesting(body, 0)).orElse(0);
    }

    private static int nesting(Node node, int depth) {
        int current = isNesting(node) ? depth + 1 : depth;
        int max = current;
        for (Node child : node.getChildNodes()) {
            max = Math.max(max, nesting(child, current));
        }
        return max;
    }

    private static boolean isNesting(Node node) {
        return Ast.isLoop(node) || node instanceof IfStmt || node instanceof SwitchStmt
                || node instanceof TryStmt || node instanceof CatchClause;
    }

    /** Оценка Big O метода. */
    public BigO complexity(MethodDeclaration method) {
        return method.getBody().map(body -> estimate(body, 0)).orElse(BigO.constant());
    }

    private BigO estimate(Node node, int loopDepth) {
        BigO best = BigO.constant();
        if (Ast.isLoop(node)) {
            best = BigO.of(loopDepth + 1);
        } else if (node instanceof MethodCallExpr call) {
            BigO callCost = costModel.cost(call);
            best = callCost.times(BigO.of(loopDepth));
        }
        int childDepth = Ast.isLoop(node) ? loopDepth + 1 : loopDepth;
        for (Node child : node.getChildNodes()) {
            best = best.max(estimate(child, childDepth));
        }
        return best;
    }
}
