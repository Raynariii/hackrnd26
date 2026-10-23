package io.blueprints.api.web;

import io.blueprints.api.runner.RunnerClient;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.web.Dto.RunProjectRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class ExecutionController {

    private final ProjectStore store;
    private final RunnerClient runner;

    public ExecutionController(ProjectStore store, RunnerClient runner) {
        this.store = store;
        this.runner = runner;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable String projectId, @RequestBody(required = false) RunProjectRequest request) {
        ProjectSession session = store.get(projectId);
        RunProjectRequest r = request == null ? new RunProjectRequest("", null, null) : request;
        var files = r.version() == null ? session.files() : session.files(r.version());
        return runner.run(files, r.stdin(), r.timeoutMs() == null ? 10_000 : r.timeoutMs());
    }

    @PostMapping("/verify")
    public Object verify(@PathVariable String projectId) {
        throw ApiException.notImplemented("Дифф-тесты (роль 4)");
    }

    @GetMapping("/export")
    public Object export(@PathVariable String projectId) {
        throw ApiException.notImplemented("Экспорт (роль 4)");
    }
}
