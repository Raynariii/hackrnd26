package io.blueprints.api.web;

import io.blueprints.analysis.AnalysisReport;
import io.blueprints.analysis.Analyzer;
import io.blueprints.analysis.Decomposer;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixEngine;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.analysis.MethodMetrics;
import io.blueprints.api.diff.UnifiedDiff;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.store.VersionConflictException;
import io.blueprints.api.verify.QualityGate;
import io.blueprints.api.web.Dto.ChangeResult;
import io.blueprints.api.web.Dto.DecomposeRequest;
import io.blueprints.api.web.Dto.DecomposeResult;
import io.blueprints.api.web.Dto.ProposedClass;
import io.blueprints.api.web.Dto.VerifyReport;
import io.blueprints.api.web.Dto.VersionedRequest;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class AnalysisController {

    private final ProjectStore store;
    private final Analyzer analyzer;
    private final FixEngine fixEngine;
    private final Decomposer decomposer;
    private final JavaProjectParser parser;
    private final QualityGate qualityGate;

    public AnalysisController(ProjectStore store, Analyzer analyzer, FixEngine fixEngine, Decomposer decomposer,
                              JavaProjectParser parser, QualityGate qualityGate) {
        this.store = store;
        this.analyzer = analyzer;
        this.fixEngine = fixEngine;
        this.decomposer = decomposer;
        this.parser = parser;
        this.qualityGate = qualityGate;
    }

    @GetMapping("/diagnostics")
    public Diagnostics diagnostics(@PathVariable String projectId) {
        ProjectSession session = store.get(projectId);
        AnalysisReport report = analyzer.analyze(session.model());
        return new Diagnostics(session.version(), report);
    }

    /**
     * Авто-исправление находки: FixEngine правит AST копии → quality gate (компиляция + дифф-тесты против
     * текущей версии) → новая версия. Если поведение разошлось — 422, сессия не меняется.
     */
    @PostMapping("/fixes/{findingId}")
    public ChangeResult fix(@PathVariable String projectId, @PathVariable String findingId,
                            @RequestBody(required = false) VersionedRequest request) {
        ProjectSession session = store.get(projectId);
        synchronized (session) {
            int version = session.version();
            if (request != null && request.version() != version) {
                throw new VersionConflictException(request.version(), version);
            }
            List<SourceFile> before = session.files();
            FixEngine.Applied applied;
            try {
                applied = fixEngine.apply(before, findingId);
            } catch (FixNotApplicableException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "fix_not_applicable", e.getMessage(), List.of());
            }
            ProjectModel model = parseOrFail(applied.files());
            VerifyReport report = qualityGate.check(session, applied.files(), "Исправление «" + applied.description() + "»");
            session.commit(version, applied.files(), model);
            return new ChangeResult(ProjectController.state(session), UnifiedDiff.of(before, applied.files()),
                    applied.description(), report);
        }
    }

    /** v1: только предложение разбиения (apply=false); автоприменение — 501, см. LEAD.md G7. */
    @PostMapping("/decompose")
    public DecomposeResult decompose(@PathVariable String projectId, @RequestBody(required = false) DecomposeRequest request) {
        ProjectSession session = store.get(projectId);
        if (request != null && Boolean.TRUE.equals(request.apply())) {
            throw ApiException.notImplemented("Автоприменение декомпозиции (apply=true)");
        }
        Decomposer.Proposal proposal = decomposer.propose(session.model());
        List<ProposedClass> classes = proposal.proposal().stream()
                .map(c -> new ProposedClass(c.className(), c.responsibility(), c.members()))
                .toList();
        return new DecomposeResult(classes, proposal.notes(), null);
    }

    private ProjectModel parseOrFail(List<SourceFile> files) {
        ProjectModel model = parser.parse(files);
        if (model.hasErrors()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "parse_error",
                    "Исправление дало код, который не разбирается", model.problems());
        }
        return model;
    }

    public record Diagnostics(int version, List<Finding> findings, List<MethodMetrics> metrics) {
        Diagnostics(int version, AnalysisReport report) {
            this(version, report.findings(), report.metrics());
        }
    }
}
