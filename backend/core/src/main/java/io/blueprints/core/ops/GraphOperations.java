package io.blueprints.core.ops;

import io.blueprints.core.model.ProjectModel;

/** Применяет операцию графа к AST модели. После вызова модель переиндексирована. */
public interface GraphOperations {

    void apply(ProjectModel model, GraphOp op);
}
