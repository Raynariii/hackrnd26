package io.blueprints.api.runner;

import io.blueprints.core.model.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Клиент сервиса-песочницы (POST /internal/run). Ответ пробрасывается как есть — формат RunResult. */
@Component
public class RunnerClient {

    private final RestClient client;

    public RunnerClient(@Value("${runner.url:http://localhost:8090}") String runnerUrl) {
        this.client = RestClient.create(runnerUrl);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(List<SourceFile> files, String stdin, int timeoutMs) {
        return client.post()
                .uri("/internal/run")
                .body(Map.of("files", files, "stdin", stdin == null ? "" : stdin, "timeoutMs", timeoutMs))
                .retrieve()
                .body(Map.class);
    }
}
