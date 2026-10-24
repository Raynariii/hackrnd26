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
 * <p>Новое правило = класс, реализующий {@link Rule}, строка в {@link Rules#defaultRules()} и тест
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

    /** Реестр правил по умолчанию живёт в {@code rules/Rules.java}. */
    public static List<Rule> defaultRules() {
        return Rules.defaultRules();
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
        findings.replaceAll(finding -> withApplicability(model, finding));
        // Панель «Проблемы» показывает сначала критичное и сверху вниз по коду.
        findings.sort(Comparator.comparing(Finding::severity).thenComparingInt(Finding::startLine));
        return new AnalysisReport(findings, metrics(model));
    }

    /**
     * Авто-применимость решает реестр исправлений ({@link Fixers}), а не правило: если
     * {@code FixEngine} на этой версии кода отказал бы (коллекция меняется в цикле, строку читают
     * внутри цикла, …), в диагностике стоит {@code autoApplicable=false}. Иначе кнопка «Применить»
     * обещает то, чего движок не сделает (запрос роли 4).
     */
    private static Finding withApplicability(ProjectModel model, Finding finding) {
        Fix fix = finding.fix();
        if (fix == null) {
            return finding;
        }
        boolean applicable = Fixers.supports(model, finding);
        return new Finding(
                finding.id(),
                finding.rule(),
                finding.severity(),
                finding.title(),
                finding.explanation(),
                finding.file(),
                finding.startLine(),
                finding.endLine(),
                finding.nodeIds(),
                finding.complexity(),
                new Fix(fix.kind(), fix.description(), fix.preview(), applicable),
                finding.expectedEffect());
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
