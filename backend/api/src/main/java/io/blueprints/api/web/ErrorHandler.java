package io.blueprints.api.web;

import io.blueprints.api.store.ProjectNotFoundException;
import io.blueprints.api.store.VersionConflictException;
import io.blueprints.api.web.Dto.ErrorResponse;
import io.blueprints.core.ops.UnsupportedGraphOpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage(), e.problems()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(ProjectNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<ErrorResponse> conflict(VersionConflictException e) {
        return error(HttpStatus.CONFLICT, "version_conflict", e.getMessage());
    }

    @ExceptionHandler({UnsupportedGraphOpException.class, UnsupportedOperationException.class})
    ResponseEntity<ErrorResponse> unsupported(RuntimeException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_op", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, List.of()));
    }
}
