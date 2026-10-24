package io.blueprints.api.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.core.model.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Клиент сервиса-песочницы (POST /internal/run, /internal/run-batch). */
@Component
public class RunnerClient implements BatchRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient client;
    private final String runnerUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public RunnerClient(@Value("${runner.url:http://localhost:8090}") String runnerUrl) {
        this.client = RestClient.create(runnerUrl);
        this.runnerUrl = runnerUrl;
    }

    /**
     * Потоковый запуск: события SSE песочницы (compile / stdout / stderr / exit / error) копируются в {@code out}
     * по мере поступления — консоль показывает вывод, пока программа работает.
     */
    public void stream(List<SourceFile> files, String stdin, int timeoutMs, OutputStream out) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("files", files);
        body.put("stdin", stdin == null ? "" : stdin);
        body.put("timeoutMs", timeoutMs);
        HttpRequest request = HttpRequest.newBuilder(URI.create(runnerUrl + "/internal/run-stream"))
                .timeout(Duration.ofMillis(timeoutMs + 60_000L))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    String message = new String(in.readNBytes(2000), java.nio.charset.StandardCharsets.UTF_8);
                    writeError(out, "песочница ответила " + response.statusCode() + ": " + message);
                    return;
                }
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(out, "запуск прерван");
        } catch (java.net.ConnectException e) {
            writeError(out, "песочница недоступна");
        }
    }

    private static void writeError(OutputStream out, String message) throws IOException {
        out.write(("event: error\ndata: " + JSON.writeValueAsString(Map.of("message", message)) + "\n\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
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
