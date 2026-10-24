package io.blueprints.api.web;

import io.blueprints.analysis.MetricsCalculator;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.store.VersionConflictException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * POST /complexity — Big O фрагмента: выделенные блоки блок-схемы → их AST-поддеревья с учётом циклов-предков
 * (запрос роли 2a, библиотечная часть — {@link MetricsCalculator#complexityOf}). Только чтение: модель не меняется.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ComplexityController {

    public record ComplexityRequest(Integer version, List<String> nodeIds) {
    }

    public record ComplexityResponse(int version, String current) {
    }

    private final ProjectStore store;
    private final MetricsCalculator metrics = new MetricsCalculator();

    public ComplexityController(ProjectStore store) {
        this.store = store;
    }

    @PostMapping("/complexity")
    public ComplexityResponse complexity(@PathVariable String projectId, @RequestBody ComplexityRequest request) {
        if (request == null || request.nodeIds() == null || request.nodeIds().isEmpty()) {
            throw new IllegalArgumentException("Нужен хотя бы один id блока в nodeIds");
        }
        ProjectSession session = store.get(projectId);
        synchronized (session) {
            int version = session.version();
            // id блоков меняются от версии к версии: оценка по устаревшим id была бы про другой код
            if (request.version() != null && request.version() != version) {
                throw new VersionConflictException(request.version(), version);
            }
            return new ComplexityResponse(version, metrics.complexityOf(session.model(), request.nodeIds()).toString());
        }
    }
}
