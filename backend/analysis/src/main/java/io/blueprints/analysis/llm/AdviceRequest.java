package io.blueprints.analysis.llm;

/**
 * Что видит советчик: находка анализатора и код, которого она касается.
 *
 * @param ruleId      стабильный id правила ({@code Finding.rule()}), например {@code collection-lookup-in-loop}
 * @param title       заголовок находки для пользователя
 * @param explanation объяснение находки
 * @param file        файл проекта
 * @param code        код метода или фрагмента, который нужно улучшить
 */
public record AdviceRequest(String ruleId, String title, String explanation, String file, String code) {
}
