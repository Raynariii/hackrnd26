package io.blueprints.api.web;

import io.blueprints.analysis.AnalysisReport;
import io.blueprints.analysis.Analyzer;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class AnalysisController {

    private final ProjectStore store;
    private final Analyzer analyzer;

    public AnalysisController(ProjectStore store, Analyzer analyzer) {
        this.store = store;
        this.analyzer = analyzer;
    }

    @GetMapping("/diagnostics")
    public Diagnostics diagnostics(@PathVariable String projectId) {
        ProjectSession session = store.get(projectId);
        AnalysisReport report = analyzer.analyze(session.model());
        return new Diagnostics(session.version(), report);
    }

    @PostMapping("/fixes/{findingId}")
    public Object fix(@PathVariable String projectId, @PathVariable String findingId) {
        throw ApiException.notImplemented("Применение исправлений (роль 2)");
    }

    @PostMapping("/decompose")
    public Object decompose(@PathVariable String projectId) {
        throw ApiException.notImplemented("Декомпозиция (роль 2)");
    }

    public record Diagnostics(int version, java.util.List<io.blueprints.analysis.Finding> findings,
                              java.util.List<io.blueprints.analysis.MethodMetrics> metrics) {
        Diagnostics(int version, AnalysisReport report) {
            this(version, report.findings(), report.metrics());
        }
    }
}
