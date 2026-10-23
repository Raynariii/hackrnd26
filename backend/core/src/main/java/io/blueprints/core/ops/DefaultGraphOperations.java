package io.blueprints.core.ops;

import io.blueprints.core.model.ProjectModel;

/** TODO(роль 1): editNode, insertAfter, delete, move, wrapIf, wrapLoop, extractMethod. */
public final class DefaultGraphOperations implements GraphOperations {

    @Override
    public void apply(ProjectModel model, GraphOp op) {
        throw new UnsupportedGraphOpException("Операция '" + op.op() + "' ещё не реализована (роль 1)");
    }
}
