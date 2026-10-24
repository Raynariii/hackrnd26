package io.blueprints.api.runner;

import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.core.model.SourceFile;

import java.util.List;

/** Компиляция один раз и запуск на нескольких stdin — POST /internal/run-batch песочницы. */
@FunctionalInterface
public interface BatchRunner {

    RunBatchResult runBatch(List<SourceFile> files, List<String> inputs, int timeoutMs);
}
