package io.blueprints.core.graph;

import io.blueprints.core.model.ProjectModel;

public interface GraphBuilder {

    /** Классы, методы, поля и вызовы всего проекта. */
    Graph overview(ProjectModel model);

    /** Блок-схема тела одного метода. {@code methodNodeId} — id узла kind=method из overview. */
    Graph methodFlow(ProjectModel model, String methodNodeId);
}
