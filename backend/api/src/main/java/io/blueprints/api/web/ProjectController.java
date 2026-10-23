package io.blueprints.api.web;

import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.web.Dto.CreateProjectRequest;
import io.blueprints.api.web.Dto.ProjectState;
import io.blueprints.api.web.Dto.UpdateSourceRequest;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphBuilder;
import io.blueprints.core.graph.GraphLevel;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import io.blueprints.core.ops.GraphOp;
import io.blueprints.core.ops.GraphOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectStore store;
    private final JavaProjectParser parser;
    private final CodeGenerator codeGenerator;
    private final GraphBuilder graphBuilder;
    private final GraphOperations graphOperations;

    public ProjectController(ProjectStore store, JavaProjectParser parser, CodeGenerator codeGenerator,
                             GraphBuilder graphBuilder, GraphOperations graphOperations) {
        this.store = store;
        this.parser = parser;
        this.codeGenerator = codeGenerator;
        this.graphBuilder = graphBuilder;
        this.graphOperations = graphOperations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectState create(@RequestBody CreateProjectRequest request) {
        if (request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("Нужен хотя бы один файл");
        }
        ProjectModel model = parseOrFail(request.files());
        ProjectSession session = store.create(request.name() == null ? "project" : request.name(), request.files(), model);
        return state(session);
    }

    @GetMapping("/{projectId}")
    public ProjectState get(@PathVariable String projectId) {
        return state(store.get(projectId));
    }

    @PutMapping("/{projectId}/source")
    public ProjectState updateSource(@PathVariable String projectId, @RequestBody UpdateSourceRequest request) {
        ProjectSession session = store.get(projectId);
        ProjectModel model = parseOrFail(request.files());
        session.commit(request.version(), request.files(), model);
        return state(session);
    }

    @GetMapping("/{projectId}/graph")
    public Graph graph(@PathVariable String projectId,
                       @RequestParam(defaultValue = "overview") String level,
                       @RequestParam(required = false) String method) {
        ProjectModel model = store.get(projectId).model();
        if (GraphLevel.parse(level) == GraphLevel.METHOD) {
            if (method == null) {
                throw new IllegalArgumentException("Параметр method обязателен при level=method");
            }
            return graphBuilder.methodFlow(model, method);
        }
        return graphBuilder.overview(model);
    }

    @PostMapping("/{projectId}/graph/ops")
    public ProjectState applyOp(@PathVariable String projectId, @RequestBody GraphOpRequest request) {
        ProjectSession session = store.get(projectId);
        synchronized (session) {
            // Операция меняет AST на копии: перепарсим текущие исходники, чтобы при ошибке не испортить сессию.
            ProjectModel working = parser.parse(session.files());
            graphOperations.apply(working, request.toOp());
            List<SourceFile> files = codeGenerator.print(working);
            session.commit(request.version(), files, parseOrFail(files));
        }
        return state(session);
    }

    private ProjectModel parseOrFail(List<SourceFile> files) {
        ProjectModel model = parser.parse(files);
        if (model.hasErrors()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "parse_error", "Код не разбирается", model.problems());
        }
        return model;
    }

    private static ProjectState state(ProjectSession session) {
        return new ProjectState(session.id(), session.name(), session.version(), session.files(),
                session.model().problems());
    }

    public record GraphOpRequest(int version, String op, String nodeId, List<String> nodeIds, String code,
                                 io.blueprints.core.graph.NodeKind kind, String targetId, String name) {
        GraphOp toOp() {
            return new GraphOp(op, nodeId, nodeIds, code, kind, targetId, name);
        }
    }
}
