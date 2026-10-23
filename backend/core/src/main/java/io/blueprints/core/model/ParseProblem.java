package io.blueprints.core.model;

public record ParseProblem(String file, Integer line, Integer column, String message) {
}
