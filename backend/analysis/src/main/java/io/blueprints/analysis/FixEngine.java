package io.blueprints.analysis;

import io.blueprints.analysis.fixes.FixAnchors;
import io.blueprints.analysis.fixes.Fixer;
import io.blueprints.analysis.fixes.Fixers;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.List;

/**
 * Детерминированные авто-исправления: фасад модуля для роли 4
 * ({@code FixEngine#apply(files, findingId)}).
 *
 * <p>Работает так же, как операции над графом в ядре: парсим <em>копию</em> текущих исходников,
 * меняем AST, печатаем через {@link CodeGenerator} (форматирование сохраняется). Сессия
 * пользователя не портится: при ошибке ничего не применяется.
 *
 * <p>Сами правки разнесены по классам {@code fixes/*Fix} и зарегистрированы в {@link Fixers};
 * здесь — поиск находки, выбор исправления по {@code Fix.kind} и печать кода. Причина отказа —
 * та же, что показывает диагностика в {@code Fix.autoApplicable}, поэтому кнопка «Применить»
 * не обещает того, чего движок не сделает.
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
        Fixer fixer = Fixers.byKind(finding.fix().kind())
                .orElseThrow(() -> new FixNotApplicableException(FixAnchors.manual(finding)));
        String refusal = fixer.refusalReason(model, finding);
        if (refusal != null) {
            throw new FixNotApplicableException(refusal);
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
