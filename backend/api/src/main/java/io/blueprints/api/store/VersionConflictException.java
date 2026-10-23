package io.blueprints.api.store;

public class VersionConflictException extends RuntimeException {

    public VersionConflictException(int requested, int current) {
        super("Изменение основано на версии " + requested + ", текущая версия " + current);
    }
}
