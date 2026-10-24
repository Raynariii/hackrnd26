package io.blueprints.api.verify;

import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.web.ApiException;
import io.blueprints.api.web.Dto.VerifyCase;
import io.blueprints.api.web.Dto.VerifyReport;
import io.blueprints.api.web.Dto.VerifyRequest;
import io.blueprints.core.model.SourceFile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Quality gate (CLAUDE.md: «оптимизация применяется только после зелёного quality gate»): новая версия
 * компилируется и на тех же входах ведёт себя как текущая. Иначе — 422, сессия не меняется.
 */
@Component
public class QualityGate {

    private final VerifyService verifyService;
    private final CorpusInputs corpus;

    public QualityGate(VerifyService verifyService, CorpusInputs corpus) {
        this.verifyService = verifyService;
        this.corpus = corpus;
    }

    public VerifyReport check(ProjectSession session, List<SourceFile> candidate, String what) {
        // Пример корпуса узнаём по исходной версии 1: после первых правок текущие файлы с корпусом уже не совпадут.
        List<String> inputs = new ArrayList<>();
        corpus.find(session.files(1)).ifPresent(ex -> ex.inputs().forEach(in -> inputs.add(in.stdin())));
        int version = session.version();
        VerifyReport report = verifyService.verify(session.files(version), version, candidate, version + 1,
                session.savedInputs(), new VerifyRequest(inputs, true, false, null));
        if (!report.compiled() || !report.behaviorPreserved()) {
            String reason = report.cases().stream().filter(c -> !c.ok()).findFirst()
                    .map(VerifyCase::mismatch).orElse("нет ни одного проверочного входа");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "quality_gate_failed",
                    what + " отклонено quality gate: " + (report.compiled() ? "поведение изменилось ("
                            + report.passed() + "/" + report.total() + " тестов), " : "") + reason, List.of());
        }
        return report;
    }
}
