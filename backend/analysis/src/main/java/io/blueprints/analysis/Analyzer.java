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

    public AnalysisReport analyze(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            findings.addAll(rule.check(model));
        }
        return new AnalysisReport(findings, metrics(model));
    }

    /** Метрики по каждому методу: строки, цикломатика, вложенность, Big O. */
    private List<MethodMetrics> metrics(ProjectModel model) {
        List<MethodMetrics> result = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                result.add(metricsCalculator.forMethod(model, file, method));
            }
        });
        return result;
    }
}
