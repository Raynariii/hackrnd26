package io.blueprints.api.advice;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import io.blueprints.analysis.Analyzer;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.llm.Advice;
import io.blueprints.analysis.llm.AdviceRequest;
import io.blueprints.analysis.llm.OptimizationAdvisor;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.store.ProjectStore;
import io.blueprints.api.web.ApiException;
import io.blueprints.core.model.SourceFile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * POST /advice — совет LLM по находке (запрос роли 2d, опционально, D6). Советчик видит только находку и код её
 * метода; ответ — текст. Применить его можно только правкой кода, а она, как и любая правка, проверяется /verify.
 * Без ANTHROPIC_API_KEY советчик выключен → 204.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class AdviceController {

    /** Не отправляем наружу больше этого — метод на тысячу строк советчику всё равно не нужен целиком. */
    static final int MAX_CODE_LINES = 200;

    public record AdviceQuery(String findingId) {
    }

    public record AdviceResponse(String findingId, String suggestion, String model) {
    }

    private final ProjectStore store;
    private final Analyzer analyzer;
    private final OptimizationAdvisor advisor;

    public AdviceController(ProjectStore store, Analyzer analyzer, OptimizationAdvisor advisor) {
        this.store = store;
        this.analyzer = analyzer;
        this.advisor = advisor;
    }

    @PostMapping("/advice")
    public ResponseEntity<AdviceResponse> advice(@PathVariable String projectId, @RequestBody AdviceQuery query) {
        if (query == null || query.findingId() == null || query.findingId().isBlank()) {
            throw new IllegalArgumentException("Нужен findingId");
        }
        ProjectSession session = store.get(projectId);
        Finding finding = analyzer.analyze(session.model()).findings().stream()
                .filter(f -> f.id().equals(query.findingId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found",
                        "Находка " + query.findingId() + " не актуальна: перечитайте диагностику", List.of()));
        if (!advisor.enabled()) {
            return ResponseEntity.noContent().build();
        }
        String code = codeFor(session, finding);
        Optional<Advice> advice = advisor.advise(new AdviceRequest(finding.rule(), finding.title(),
                finding.explanation(), finding.file(), code));
        return advice
                .map(a -> ResponseEntity.ok(new AdviceResponse(finding.id(), a.suggestion(), a.model())))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY, "llm_unavailable",
                        "Советчик не ответил — попробуйте позже", List.of()));
    }

    /** Код метода (или конструктора), в котором находка; если она вне метода — строки самой находки. */
    static String codeFor(ProjectSession session, Finding finding) {
        String content = session.files().stream().filter(f -> f.path().equals(finding.file()))
                .map(SourceFile::content).findFirst().orElse("");
        String[] lines = content.split("\r?\n", -1);
        int from = finding.startLine();
        int to = finding.endLine();
        CompilationUnit cu = session.model().units().get(finding.file());
        if (cu != null) {
            Optional<CallableDeclaration> enclosing = cu.findAll(CallableDeclaration.class).stream()
                    .filter(c -> c.getRange().isPresent())
                    .filter(c -> c.getRange().get().begin.line <= finding.startLine()
                            && c.getRange().get().end.line >= finding.endLine())
                    .min(Comparator.comparingInt(c -> c.getRange().get().end.line - c.getRange().get().begin.line));
            if (enclosing.isPresent()) {
                from = enclosing.get().getRange().get().begin.line;
                to = enclosing.get().getRange().get().end.line;
            }
        }
        from = Math.max(1, from);
        to = Math.min(lines.length, Math.max(from, Math.min(to, from + MAX_CODE_LINES - 1)));
        return String.join("\n", Arrays.copyOfRange(lines, from - 1, to));
    }
}
