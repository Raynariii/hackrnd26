package io.blueprints.api.runner;

import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.core.model.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Клиент сервиса-песочницы (POST /internal/run, /internal/run-batch). */
@Component
public class RunnerClient implements BatchRunner {

    private final RestClient client;

    public RunnerClient(@Value("${runner.url:http://localhost:8090}") String runnerUrl) {
        this.client = RestClient.create(runnerUrl);
    }

    /** Ответ пробрасывается как есть — формат RunResult. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(List<SourceFile> files, String stdin, int timeoutMs) {
        return client.post()
                .uri("/internal/run")
                .body(Map.of("files", files, "stdin", stdin == null ? "" : stdin, "timeoutMs", timeoutMs))
                .retrieve()
                .body(Map.class);
    }

    @Override
    public RunBatchResult runBatch(List<SourceFile> files, List<String> inputs, int timeoutMs) {
        Map<String, Object> body = new HashMap<>();
        body.put("files", files);
        body.put("inputs", inputs);
        body.put("timeoutMs", timeoutMs);
        return client.post()
                .uri("/internal/run-batch")
                .body(body)
                .retrieve()
                .body(RunBatchResult.class);
    }
}
