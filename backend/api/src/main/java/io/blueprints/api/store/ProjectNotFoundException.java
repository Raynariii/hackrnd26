package io.blueprints.api.store;

public class ProjectNotFoundException extends RuntimeException {

    public ProjectNotFoundException(String id) {
        super("Проект не найден: " + id);
    }
}
