package io.blueprints.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.blueprints.analysis.fixes.Fixers;
import io.blueprints.analysis.rules.Rules;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Анализатор: чистый набор правил + метрики. Без HTTP, без состояния — вызывается из {@code api}
 * как библиотека ({@code Analyzer.withDefaultRules()}).
 *
 * <p>Новое правило = класс, реализующий {@link Rule}, строка в {@link Rules#all()} и тест.
 * Применимость авто-исправления решает реестр {@link Fixers}, а не правило: здесь
 * {@code Fix.autoApplicable} выставляется по {@code Fixers.supports} — так кнопка «Применить»
 * в UI совпадает с тем, что сделает {@code POST /fixes} (`PLAN.md` §6.1, п. 3).
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

    /** Реестр правил модуля ({@link Rules}). */
    public static List<Rule> defaultRules() {
        return Rules.all();
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
        List<Finding> withApplicability = findings.stream()
                .map(finding -> applyFixability(finding, model))
                .toList();
        return new AnalysisReport(withApplicability, metrics(model));
    }

    /**
     * {Fix.autoApplicable} и текст отказа берутся из реестра фиксеров: правило только описывает,
     * что предлагает сделать. Правила без фиксера остаются как есть (всегда вручную).
     */
    private static Finding applyFixability(Finding finding, ProjectModel model) {
        if (finding.fix() == null || Fixers.forRule(finding.rule()).isEmpty()) {
            return finding;
        }
        String reason = Fixers.explain(finding, model);
        Fix fix = finding.fix();
        Fix updated = new Fix(fix.kind(), reason != null ? reason : fix.description(), fix.preview(), reason == null);
        return new Finding(finding.id(), finding.rule(), finding.severity(), finding.title(), finding.explanation(),
                finding.file(), finding.startLine(), finding.endLine(), finding.nodeIds(), finding.complexity(),
                updated, finding.expectedEffect());
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
