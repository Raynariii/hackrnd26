package io.blueprints.api.web;

import io.blueprints.api.export.ExportService;
import io.blueprints.api.runner.RunnerClient;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.verify.VerifyService;
import io.blueprints.api.web.Dto.RunProjectRequest;
import io.blueprints.api.web.Dto.VerifyReport;
import io.blueprints.api.web.Dto.VerifyRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ExportService exportService;

    public ExecutionController(ProjectStore store, RunnerClient runner, VerifyService verifyService,
                               ExportService exportService) {
        this.store = store;
        this.runner = runner;
        this.verifyService = verifyService;
        this.exportService = exportService;
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

    /** Zip: текущие исходники + golden-master тесты JUnit против версии 1 + отчёт проверки (см. ExportService). */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String projectId) {
        ExportService.Export export = exportService.export(store.get(projectId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.fileName()).build().toString())
                .header("X-Verify-Passed", export.report().passed() + "/" + export.report().total())
                .header("X-Behavior-Preserved", String.valueOf(export.report().behaviorPreserved()))
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Verify-Passed, X-Behavior-Preserved, Content-Disposition")
                .body(export.zip());
    }
}
