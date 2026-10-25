package io.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
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

import java.util.Collection;

/**
 * Метрики метода по PLAN.md §4: цикломатическая сложность, максимальная вложенность блоков
 * и оценка Big O из AST.
 *
 * <p>Big O выводится из двух вещей: глубины вложенности циклов (каждый цикл — множитель n)
 * и стоимости операций внутри них по таблице {@link CostModel}. Терминальная операция Stream API
 * считается одним проходом O(n), а лямбда в {@code filter}/{@code map}/{@code anyMatch} выполняется
 * на каждом элементе — линейный поиск в лямбде даёт O(n²). Оценка детерминированная
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

    /**
     * Оценка Big O фрагмента: выбранные блоки блок-схемы (id узлов графа) → их AST-поддеревья.
     *
     * <p>Циклы-предки учитываются: блок внутри двух циклов исполняется O(n²) раз, поэтому фрагмент
     * показывает ту же сложность, что и метод, но только для выбранной части. Синтетический блок
     * {@code <id метода>:start} даёт сложность метода целиком; неизвестные id игнорируются.
     * Чистая функция: модель не меняется, применения к графу нет.
     */
    public BigO complexityOf(ProjectModel model, Collection<String> nodeIds) {
        if (model == null || nodeIds == null) {
            return BigO.constant();
        }
        BigO result = BigO.constant();
        for (String nodeId : nodeIds) {
            Node node = astNode(model, nodeId);
            if (node == null) {
                continue;
            }
            int loops = Ast.enclosingLoops(node).size();
            // Сам блок исполняется на каждой итерации каждого цикла-предка: внутри двух циклов — не меньше O(n²),
            // даже если это простой оператор (count++), а не цикл или вызов.
            result = result.max(BigO.of(loops)).max(estimate(node, loops));
        }
        return result;
    }

    /** Узел по id графа: AST-узлы адресуются как {@code "g" + astId} (соглашение graph.schema.json). */
    private static Node astNode(ProjectModel model, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        String id = nodeId;
        if (id.endsWith(":start") || id.endsWith(":end")) {
            id = id.substring(0, id.lastIndexOf(':'));
        }
        String astId = id.startsWith("g") ? id.substring(1) : id;
        return model.nodeById(astId).orElse(null);
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
        boolean perElement = node instanceof MethodCallExpr call && costModel.iteratesPerElement(call);
        for (Node child : node.getChildNodes()) {
            int depth = perElement && isExecutedForEveryElement(child) ? childDepth + 1 : childDepth;
            best = best.max(estimate(child, depth));
        }
        return best;
    }

    /** Лямбда/ссылка на метод, которую операция над потоком или коллекцией вызывает на каждом элементе. */
    private static boolean isExecutedForEveryElement(Node node) {
        return node instanceof LambdaExpr || node instanceof MethodReferenceExpr;
    }
}
