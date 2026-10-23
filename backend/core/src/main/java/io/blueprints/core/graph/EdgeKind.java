package io.blueprints.core.graph;

import com.fasterxml.jackson.annotation.JsonValue;

/** Типы рёбер графа. Строковые значения совпадают с contracts/graph.schema.json. */
public enum EdgeKind {
    NEXT("next"), TRUE("true"), FALSE("false"),
    LOOP_BODY("loopBody"), LOOP_BACK("loopBack"), LOOP_EXIT("loopExit"),
    CASE("case"), BREAK("break"), CONTINUE("continue"),
    EXCEPTION("exception"), FINALLY("finally"),
    CALL("call"), CONTAINS("contains");

    private final String value;

    EdgeKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
