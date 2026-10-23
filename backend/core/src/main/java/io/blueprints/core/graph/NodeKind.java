package io.blueprints.core.graph;

import com.fasterxml.jackson.annotation.JsonValue;

/** Типы узлов графа. Строковые значения совпадают с contracts/graph.schema.json. */
public enum NodeKind {
    CLASS("class"), INTERFACE("interface"), ENUM("enum"), RECORD("record"),
    METHOD("method"), CONSTRUCTOR("constructor"), FIELD("field"),
    START("start"), END("end"),
    VARIABLE("variable"), CONDITION("condition"), LOOP("loop"), CALL("call"),
    OPERATION("operation"), RETURN("return"),
    SWITCH("switch"), CASE("case"), BREAK("break"), CONTINUE("continue"),
    TRY("try"), CATCH("catch"), FINALLY("finally"), THROW("throw"),
    /** Конструкция, которую граф пока не раскладывает: показывается «как есть», но не теряется. */
    RAW("raw");

    private final String value;

    NodeKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
