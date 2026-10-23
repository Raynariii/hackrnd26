package io.blueprints.analysis;

import io.blueprints.core.model.ProjectModel;

import java.util.List;

/**
 * Правило анализа. Чистая функция над моделью: без HTTP, без состояния.
 * Новое правило = новый класс + строка в {@link Analyzer#defaultRules()}.
 */
public interface Rule {

    /** Стабильный идентификатор, например {@code nested-loop-same-collection}. */
    String id();

    List<Finding> check(ProjectModel model);
}
