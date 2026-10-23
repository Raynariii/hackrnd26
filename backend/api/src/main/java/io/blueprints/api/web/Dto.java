package io.blueprints.api.web;

import io.blueprints.core.model.ParseProblem;
import io.blueprints.core.model.SourceFile;

import java.util.List;

/** DTO REST API; имена полей совпадают с contracts/openapi.yaml. */
public final class Dto {

    private Dto() {
    }

    public record CreateProjectRequest(String name, List<SourceFile> files) {
    }

    public record UpdateSourceRequest(int version, List<SourceFile> files) {
    }

    public record VersionedRequest(int version) {
    }

    public record ProjectState(String id, String name, int version, List<SourceFile> files,
                               List<ParseProblem> parseProblems) {
    }

    public record RunProjectRequest(String stdin, Integer version, Integer timeoutMs) {
    }

    public record ErrorResponse(String error, String message, List<ParseProblem> problems) {
    }
}
