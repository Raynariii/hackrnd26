package io.blueprints.api.web;

import io.blueprints.core.model.ParseProblem;
import org.springframework.http.HttpStatus;

import java.util.List;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ParseProblem> problems;

    public ApiException(HttpStatus status, String code, String message, List<ParseProblem> problems) {
        super(message);
        this.status = status;
        this.code = code;
        this.problems = problems;
    }

    public static ApiException notImplemented(String what) {
        return new ApiException(HttpStatus.NOT_IMPLEMENTED, "not_implemented", what + " ещё не реализовано", List.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ParseProblem> problems() {
        return problems;
    }
}
