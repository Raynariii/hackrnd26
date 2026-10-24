package io.blueprints.analysis.fixes;

import io.blueprints.analysis.Finding;
import io.blueprints.core.model.ProjectModel;

/**
 * Детерминированное авто-исправление одного правила.
 *
 * <p>Реестр — {@link Fixers}. Правило только описывает исправление ({@code kind}, текст, превью);
 * поддержку и применимость решает реестр: {@link #explain} — причина отказа (её видит UI и ответ 422),
 * {@link #apply} — сама правка AST. Так новая подроль 2b добавляет фиксер, не трогая файлы правил
 * (`PLAN.md` §6.1).
 */
public interface Fixer {

    /** Правило, которое исправляет фиксер ({@code Rule.id()}). */
    String rule();

    /** Значение {@code Fix.kind} для контракта. */
    String kind();

    /** {@code null} — правку можно применить; иначе причина отказа. */
    String explain(Finding finding, ProjectModel model);

    /** Меняет AST модели и возвращает описание сделанного. */
    String apply(ProjectModel model, Finding finding);
}
