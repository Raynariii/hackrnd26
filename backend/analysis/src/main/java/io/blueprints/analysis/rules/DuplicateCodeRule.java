package io.blueprints.analysis.rules;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Дублирование кода: одинаковые последовательности операторов в разных местах.
 *
 * <p>Сравниваются нормализованные фрагменты ({@link Ast#normalize}: имена → {@code V}, числа → {@code N},
 * строковые литералы сохраняются), поэтому копия, отличающаяся только именами переменных, находится,
 * а разные сообщения в {@code println} — нет. Блок режется на окна по {@code window} операторов без
 * перекрытия; фрагменты короче {@value #MIN_NORMALIZED_LENGTH} символов и фрагменты из одинаковых
 * операторов (например, {@code x++; x++;}) игнорируются, чтобы не шуметь.
 */
public final class DuplicateCodeRule implements Rule {

    private static final String RULE = "duplicated-code";
    private static final int MIN_NORMALIZED_LENGTH = 60;

    private final int window;

    public DuplicateCodeRule(int window) {
        this.window = Math.max(2, window);
    }

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        Map<String, List<Chunk>> groups = new LinkedHashMap<>();
        model.units().forEach((file, cu) -> {
            for (BlockStmt block : cu.findAll(BlockStmt.class)) {
                List<Statement> statements = block.getStatements();
                if (statements.size() < window) {
                    continue;
                }
                for (int start = 0; start + window <= statements.size(); start += window) {
                    List<Statement> chunk = List.copyOf(statements.subList(start, start + window));
                    String key = keyOf(chunk);
                    if (key != null) {
                        groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Chunk(chunk));
                    }
                }
            }
        });
        List<Finding> findings = new ArrayList<>();
        for (List<Chunk> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            findings.add(finding(model, group));
        }
        return findings;
    }

    /** Ключ сравнения фрагмента; {@code null}, если фрагмент не годится как дубль. */
    private String keyOf(List<Statement> chunk) {
        List<String> normalized = new ArrayList<>(chunk.size());
        for (Statement statement : chunk) {
            normalized.add(Ast.normalize(statement));
        }
        long distinct = normalized.stream().distinct().count();
        if (distinct < 2) {
            return null;
        }
        String key = String.join("\n", normalized);
        return key.length() < MIN_NORMALIZED_LENGTH ? null : key;
    }

    private Finding finding(ProjectModel model, List<Chunk> group) {
        Chunk anchor = group.get(1);
        Chunk first = group.get(0);
        String lines = group.stream()
                .limit(4)
                .map(chunk -> String.valueOf(Ast.startLine(chunk.statements().get(0))))
                .collect(java.util.stream.Collectors.joining(", "));
        List<com.github.javaparser.ast.Node> nodes = group.stream()
                .limit(4)
                .map(chunk -> (com.github.javaparser.ast.Node) chunk.statements().get(0))
                .toList();
        return Findings.of(model, RULE, Severity.MEDIUM,
                "Дублирование кода",
                "Одинаковый фрагмент из " + window + " операторов встречается " + group.size()
                        + " раза (строки " + lines + "). Копии расходятся при правках и ломают поведение: "
                        + "вынесите фрагмент в общий метод.",
                anchor.statements().get(0), nodes,
                null,
                new Fix("extract-method", "Выделить повторяющийся фрагмент в общий метод", null, false),
                "Минус " + (group.size() - 1) + " копий: правка в одном месте вместо " + group.size());
    }

    private record Chunk(List<Statement> statements) {
    }
}
