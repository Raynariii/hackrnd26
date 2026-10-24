package io.blueprints.runner;

import io.blueprints.runner.RunModels.RunBatchResult;
import io.blueprints.runner.RunModels.RunResult;
import io.blueprints.runner.RunModels.RunnerBatchRequest;
import io.blueprints.runner.RunModels.RunnerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class RunController {

    private final JavaSandbox sandbox;
    private final ObjectMapper json;

    public RunController(JavaSandbox sandbox, ObjectMapper json) {
        this.sandbox = sandbox;
        this.json = json;
    }

    /** Потоковый запуск: события compile / stdout / stderr / exit (или error) в формате text/event-stream. */
    @PostMapping(value = "/internal/run-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> runStream(@RequestBody RunnerRequest request) {
        StreamingResponseBody body = out -> {
            JavaSandbox.StreamSink sink = (name, data) -> send(out, name, data);
            try {
                sandbox.runStreaming(request, sink);
            } catch (IllegalArgumentException e) {
                send(out, "error", Map.of("message", e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(out, "error", Map.of("message", "запуск прерван"));
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }

    private void send(OutputStream out, String name, Object data) throws IOException {
        byte[] frame = ("event: " + name + "\ndata: " + json.writeValueAsString(data) + "\n\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }

    @PostMapping("/internal/run")
    public RunResult run(@RequestBody RunnerRequest request) throws IOException, InterruptedException {
        return sandbox.run(request);
    }

    @PostMapping("/internal/run-batch")
    public RunBatchResult runBatch(@RequestBody RunnerBatchRequest request) throws IOException, InterruptedException {
        return sandbox.runBatch(request);
    }
}
