package io.blueprints.core.graph;

import com.fasterxml.jackson.annotation.JsonValue;

public enum GraphLevel {
    OVERVIEW("overview"), METHOD("method");

    private final String value;

    GraphLevel(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static GraphLevel parse(String value) {
        for (GraphLevel level : values()) {
            if (level.value.equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown graph level: " + value);
    }
}
