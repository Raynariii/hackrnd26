package io.blueprints.api.store;

import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.ArrayList;
import java.util.List;

/** Проект в памяти: история версий исходников (версия 1 — эталон для verify) и текущая модель. */
public final class ProjectSession {

    private final String id;
    private final String name;
    private final List<List<SourceFile>> versions = new ArrayList<>();
    private ProjectModel model;

    public ProjectSession(String id, String name, List<SourceFile> files, ProjectModel model) {
        this.id = id;
        this.name = name;
        this.versions.add(List.copyOf(files));
        this.model = model;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public synchronized int version() {
        return versions.size();
    }

    public synchronized List<SourceFile> files() {
        return versions.get(versions.size() - 1);
    }

    public synchronized List<SourceFile> files(int version) {
        if (version < 1 || version > versions.size()) {
            throw new IllegalArgumentException("Нет версии " + version);
        }
        return versions.get(version - 1);
    }

    public synchronized ProjectModel model() {
        return model;
    }

    /** Новая версия; бросает {@link VersionConflictException}, если клиент работал со старой версией. */
    public synchronized void commit(int basedOn, List<SourceFile> files, ProjectModel newModel) {
        if (basedOn != version()) {
            throw new VersionConflictException(basedOn, version());
        }
        versions.add(List.copyOf(files));
        model = newModel;
    }
}
