package io.blueprints.api.runner;

import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.core.model.SourceFile;

import java.util.List;

/** Компиляция один раз и запуск на нескольких stdin — POST /internal/run-batch песочницы. */
@FunctionalInterface
public interface BatchRunner {

    /** {@code mainClass} — FQN точки входа; null — песочница ищет класс с main сама. */
    RunBatchResult runBatch(List<SourceFile> files, String mainClass, List<String> inputs, int timeoutMs);

    default RunBatchResult runBatch(List<SourceFile> files, List<String> inputs, int timeoutMs) {
        return runBatch(files, null, inputs, timeoutMs);
    }
}
