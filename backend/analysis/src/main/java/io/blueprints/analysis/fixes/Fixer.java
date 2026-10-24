package io.blueprints.analysis.fixes;

import io.blueprints.analysis.Finding;
import io.blueprints.core.model.ProjectModel;

/**
 * Одно детерминированное авто-исправление (подроль 2b). Реализации живут в пакете {@code fixes/}
 * и регистрируются в {@link Fixers}; правило только описывает {@code Fix.kind}, а возможность
 * применить исправление решает реестр.
 *
 * <p>Проверка и правка разделены: {@link #refusalReason} ничего не меняет в AST и отвечает на
 * вопрос «сработает ли движок», а {@link #apply} вызывается только после неё. Так диагностика
 * ({@code Fix.autoApplicable}) и {@code FixEngine#apply} не расходятся.
 */
public interface Fixer {

    /** Стабильный id исправления; совпадает с {@code Fix.kind()} находки и виден фронту. */
    String kind();

    /**
     * Проверка применимости без изменения AST.
     *
     * @return {@code null}, если исправление можно применить; иначе причина отказа для пользователя —
     *         тем же текстом ответит {@code FixEngine#apply}
     */
    String refusalReason(ProjectModel model, Finding finding);

    /**
     * Применяет правку к AST модели (модель уже разобрана из копии исходников).
     *
     * @return описание изменения для ответа API
     */
    String apply(ProjectModel model, Finding finding);

    /** Есть ли смысл предлагать это исправление для находки в текущей версии кода. */
    default boolean supports(ProjectModel model, Finding finding) {
        return refusalReason(model, finding) == null;
    }
}
