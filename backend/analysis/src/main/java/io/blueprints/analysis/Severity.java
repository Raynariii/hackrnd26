package io.blueprints.analysis;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Severity {
    HIGH("high"), MEDIUM("medium"), LOW("low");

    private final String value;

    Severity(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
