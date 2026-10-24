package io.blueprints.analysis.llm;

/**
 * Предложение LLM-советчика. Это только текст: код меняет пользователь или отдельный шаг с quality gate
 * (компиляция + дифф-тесты, D6) — советчик не имеет доступа к модели проекта и ничего не применяет.
 *
 * @param suggestion объяснение и исправленный фрагмент
 * @param model      модель, которая ответила (для журнала)
 */
public record Advice(String suggestion, String model) {
}
