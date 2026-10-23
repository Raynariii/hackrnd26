package io.blueprints.api.store;

import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory хранилище: для демо персистентность не нужна. */
@Component
public class ProjectStore {

    private final Map<String, ProjectSession> sessions = new ConcurrentHashMap<>();

    public ProjectSession create(String name, List<SourceFile> files, ProjectModel model) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ProjectSession session = new ProjectSession(id, name, files, model);
        sessions.put(id, session);
        return session;
    }

    public ProjectSession get(String id) {
        ProjectSession session = sessions.get(id);
        if (session == null) {
            throw new ProjectNotFoundException(id);
        }
        return session;
    }
}
