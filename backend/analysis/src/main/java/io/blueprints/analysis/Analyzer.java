package io.blueprints.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.blueprints.analysis.rules.CollectionLookupInLoopRule;
import io.blueprints.analysis.rules.DeepNestingRule;
import io.blueprints.analysis.rules.DuplicateCodeRule;
import io.blueprints.analysis.rules.EmptyCatchRule;
import io.blueprints.analysis.rules.GodClassRule;
import io.blueprints.analysis.rules.LinkedListGetInLoopRule;
import io.blueprints.analysis.rules.LongMethodRule;
import io.blueprints.analysis.rules.MagicNumberRule;
import io.blueprints.analysis.rules.NestedLoopSameCollectionRule;
import io.blueprints.analysis.rules.StringConcatInLoopRule;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Анализатор: чистый набор правил + метрики. Без HTTP, без состояния — вызывается из {@code api}
 * как библиотека ({@code Analyzer.withDefaultRules()}).
 *
 * <p>Новое правило = класс, реализующий {@link Rule}, строка в {@link #defaultRules()} и тест
 * (правило из CLAUDE.md).
 */
public final class Analyzer {

    private final List<Rule> rules;
    private final MetricsCalculator metricsCalculator = new MetricsCalculator();

    public Analyzer(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static Analyzer withDefaultRules() {
        return new Analyzer(defaultRules());
    }

    /** Правила из PLAN.md §4 в порядке показа: сначала дефекты сложности, потом структура. */
    public static List<Rule> defaultRules() {
        return List.of(
                new NestedLoopSameCollectionRule(),
                new CollectionLookupInLoopRule(),
                new StringConcatInLoopRule(),
                new LinkedListGetInLoopRule(),
                new DeepNestingRule(4, 15),
                new LongMethodRule(40),
                new DuplicateCodeRule(5),
                new EmptyCatchRule(),
                new MagicNumberRule(),
                new GodClassRule(20, 15, 400));
    }

    public List<Rule> rules() {
        return rules;
    }

    /**
     * Диагностика проекта. Правила изолированы друг от друга: если одно правило не осилило
     * незнакомую конструкцию Java и упало, остальные всё равно дают находки — панель «Проблемы»
     * не должна обнуляться из-за одного пропущенного случая (CLAUDE.md: неподдерживаемая
     * конструкция не роняет разбор). То же для метрик: не посчитали метод — пропускаем его.
     */
    public AnalysisReport analyze(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            try {
                findings.addAll(rule.check(model));
            } catch (RuntimeException ignored) {
                // Правило не справилось с конструкцией — теряем только его находки.
            }
        }
        // Панель «Проблемы» показывает сначала критичное и сверху вниз по коду.
        findings.sort(Comparator.comparing(Finding::severity).thenComparingInt(Finding::startLine));
        return new AnalysisReport(findings, metrics(model));
    }

    /** Метрики по каждому методу: строки, цикломатика, вложенность, Big O. */
    private List<MethodMetrics> metrics(ProjectModel model) {
        List<MethodMetrics> result = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                try {
                    result.add(metricsCalculator.forMethod(model, file, method));
                } catch (RuntimeException ignored) {
                    // Метод с нестандартным AST остаётся без метрик, но не ломает остальные.
                }
            }
        });
        return result;
    }
}
