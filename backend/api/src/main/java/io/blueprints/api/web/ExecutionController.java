package io.blueprints.api.web;

import io.blueprints.api.runner.RunnerClient;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.verify.VerifyService;
import io.blueprints.api.web.Dto.RunProjectRequest;
import io.blueprints.api.web.Dto.VerifyReport;
import io.blueprints.api.web.Dto.VerifyRequest;
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
    private final VerifyService verifyService;

    public ExecutionController(ProjectStore store, RunnerClient runner, VerifyService verifyService) {
        this.store = store;
        this.runner = runner;
        this.verifyService = verifyService;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable String projectId, @RequestBody(required = false) RunProjectRequest request) {
        ProjectSession session = store.get(projectId);
        RunProjectRequest r = request == null ? new RunProjectRequest("", null, null) : request;
        var files = r.version() == null ? session.files() : session.files(r.version());
        session.rememberInput(r.stdin());
        return runner.run(files, r.stdin(), r.timeoutMs() == null ? 10_000 : r.timeoutMs());
    }

    @PostMapping("/verify")
    public VerifyReport verify(@PathVariable String projectId, @RequestBody(required = false) VerifyRequest request) {
        ProjectSession session = store.get(projectId);
        int current = session.version();
        return verifyService.verify(session.files(1), 1, session.files(current), current, session.savedInputs(), request);
    }

    @GetMapping("/export")
    public Object export(@PathVariable String projectId) {
        throw ApiException.notImplemented("Экспорт (роль 4)");
    }
}
