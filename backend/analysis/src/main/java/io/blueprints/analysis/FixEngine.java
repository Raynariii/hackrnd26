package io.blueprints.analysis;

import io.blueprints.analysis.fixes.Fixer;
import io.blueprints.analysis.fixes.Fixers;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.List;

/**
 * Фасад авто-исправлений: парсит копию исходников, находит фиксер правила в реестре
 * {@link Fixers}, проверяет применимость и применяет правку к AST, после чего печатает код.
 *
 * <p>Сами правки живут в {@code io.blueprints.analysis.fixes} — по одному классу на правило
 * ({@link Fixer}); движок только оркестрирует. Форматирование сохраняется тем же
 * {@link CodeGenerator}, что и у операций графа; при отказе ничего не меняется.
 */
public final class FixEngine {

    /** Применённое исправление: что сделали и как теперь выглядят исходники. */
    public record Applied(Finding finding, List<SourceFile> files, String description) {
    }

    private final JavaProjectParser parser = new JavaProjectParser();
    private final CodeGenerator codeGenerator = new CodeGenerator();

    /**
     * Применяет исправление к копии исходников.
     *
     * @param files     текущие файлы проекта
     * @param findingId id находки из {@code GET /diagnostics}
     * @throws FixNotApplicableException если находка не найдена или исправление небезопасно
     */
    public Applied apply(List<SourceFile> files, String findingId) {
        ProjectModel model = parser.parse(files);
        Finding finding = find(model, findingId);
        if (finding.fix() == null) {
            throw new FixNotApplicableException("У находки " + findingId + " нет предложенного исправления");
        }
        Fixer fixer = Fixers.forRule(finding.rule()).orElseThrow(() -> new FixNotApplicableException(
                "Исправление «" + finding.fix().description()
                        + "» требует ручной правки: автоматически менять код небезопасно"));
        // Тот же расчёт, что выставляет Fix.autoApplicable в диагностике: текст отказа совпадает с UI.
        String reason = fixer.explain(finding, model);
        if (reason != null) {
            throw new FixNotApplicableException(reason);
        }
        String description = fixer.apply(model, finding);
        return new Applied(finding, codeGenerator.print(model), description);
    }

    private static Finding find(ProjectModel model, String findingId) {
        return new Analyzer(Analyzer.defaultRules()).analyze(model).findings().stream()
                .filter(finding -> finding.id().equals(findingId))
                .findFirst()
                .orElseThrow(() -> new FixNotApplicableException(
                        "Находка " + findingId + " не актуальна: код изменился, перечитайте диагностику"));
    }
}
