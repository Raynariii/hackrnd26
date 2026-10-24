package io.blueprints.api.web;

import io.blueprints.api.runner.RunnerClient;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.web.Dto.RunProjectRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * POST /run/stream — запуск с потоковой консолью (SSE): события песочницы compile / stdout / stderr / exit / error
 * пробрасываются по мере появления. Обычный POST /run остаётся для тех, кому нужен результат целиком.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class RunStreamController {

    private final ProjectStore store;
    private final RunnerClient runner;

    public RunStreamController(ProjectStore store, RunnerClient runner) {
        this.store = store;
        this.runner = runner;
    }

    @PostMapping(value = "/run/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> runStream(@PathVariable String projectId,
                                                           @RequestBody(required = false) RunProjectRequest request) {
        ProjectSession session = store.get(projectId);
        RunProjectRequest r = request == null ? new RunProjectRequest("", null, null) : request;
        var files = r.version() == null ? session.files() : session.files(r.version());
        session.rememberInput(r.stdin());
        int timeoutMs = r.timeoutMs() == null ? 10_000 : r.timeoutMs();
        StreamingResponseBody body = out -> runner.stream(files, r.stdin(), timeoutMs, out);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
