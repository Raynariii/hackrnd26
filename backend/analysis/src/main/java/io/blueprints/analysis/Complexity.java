package io.blueprints.analysis;

/** Оценка сложности: текущая и после исправления (может быть null). */
public record Complexity(String current, String after) {
}
