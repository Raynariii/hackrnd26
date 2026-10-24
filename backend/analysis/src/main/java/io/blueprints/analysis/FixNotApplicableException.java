package io.blueprints.analysis;

/** Исправление нельзя применить автоматически: объясняем почему (текст показывается пользователю). */
public class FixNotApplicableException extends RuntimeException {

    public FixNotApplicableException(String message) {
        super(message);
    }
}
