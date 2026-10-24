package io.blueprints.analysis.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Дублирование кода — в двух видах.
 *
 * <p><b>Точные копии:</b> блок режется на окна по {@code window} операторов без перекрытия, фрагменты
 * сравниваются нормализованными ({@link Ast#normalize}: имена → {@code V}, числа → {@code N}, комментарии
 * отброшены, строковые литералы сохранены). Ловится копия, отличающаяся только именами, и не считается
 * дублем разные сообщения в {@code println}.
 *
 * <p><b>Почти-копии:</b> на реальном коде копии редко совпадают байт-в-байт — та же логика бывает с
 * фильтром на уровень выше или с другим порядком проверок. Поэтому дополнительно сравниваются тела
 * методов одного класса и ветви одной {@code if}-цепочки по множествам токенов (Jaccard): при
 * совпадении ≥ 75 % (ветви — ≥ 80 %) и достаточном размере фрагмента появляется находка. Похожие
 * фрагменты группируются в одну находку, чтобы не выдавать все пары одного кластера.
 *
 * <p>Порог по числу токенов отсекает короткие совпадения вида {@code if (x) continue;}: их в коде много,
 * и дублями они не являются.
 */
public final class DuplicateCodeRule implements Rule {

    private static final String RULE = "duplicated-code";
    private static final int MIN_NORMALIZED_LENGTH = 60;

    /** Порог похожести и минимум токенов для тел методов. */
    private static final double METHOD_SIMILARITY = 0.85;
    private static final int METHOD_MIN_TOKENS = 12;

    /** Порог для ветвей одного if: фрагменты короче, поэтому порог и минимум ниже. */
    private static final double BRANCH_SIMILARITY = 0.85;
    private static final int BRANCH_MIN_TOKENS = 8;

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
        List<Finding> findings = new ArrayList<>(exactDuplicates(model));
        Set<String> seen = new HashSet<>();
        model.units().forEach((file, cu) -> {
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                findings.addAll(similarMethods(model, type, seen));
                for (IfStmt root : cu.findAll(IfStmt.class)) {
                    if (root.getParentNode().filter(IfStmt.class::isInstance).isPresent()) {
                        continue;   // else-if обрабатывается вместе со своей цепочкой
                    }
                    findings.addAll(similarBranches(model, branches(root), seen));
                }
            }
        });
        return findings;
    }

    // ---------------------------------------------------------------- точные копии

    private List<Finding> exactDuplicates(ProjectModel model) {
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
            if (group.size() >= 2) {
                findings.add(exactFinding(model, group));
            }
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

    private Finding exactFinding(ProjectModel model, List<Chunk> group) {
        Chunk anchor = group.get(1);
        String lines = group.stream()
                .limit(4)
                .map(chunk -> String.valueOf(Ast.startLine(chunk.statements().get(0))))
                .collect(Collectors.joining(", "));
        List<Node> nodes = group.stream()
                .limit(4)
                .map(chunk -> (Node) chunk.statements().get(0))
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

    // ---------------------------------------------------------------- почти-копии

    private List<Finding> similarMethods(ProjectModel model, ClassOrInterfaceDeclaration type, Set<String> seen) {
        List<MethodDeclaration> methods = type.getMethods().stream()
                .filter(method -> method.getBody().isPresent())
                .toList();
        List<ScoredPair> pairs = new ArrayList<>();
        for (int first = 0; first < methods.size(); first++) {
            for (int second = first + 1; second < methods.size(); second++) {
                String leftText = Ast.normalize(methods.get(first).getBody().orElseThrow());
                String rightText = Ast.normalize(methods.get(second).getBody().orElseThrow());
                if (leftText.equals(rightText)) {
                    continue;   // точная копия: её уже поймал проход по окнам операторов
                }
                double value = similarity(methods.get(first).getBody().orElseThrow(),
                        methods.get(second).getBody().orElseThrow(), METHOD_MIN_TOKENS);
                if (value >= METHOD_SIMILARITY) {
                    pairs.add(new ScoredPair(first, second, value));
                }
            }
        }
        List<Node> anchors = new ArrayList<>();
        methods.forEach(anchors::add);
        return reportPairs(model, seen, pairs, anchors, (left, right) -> "тела методов "
                + ((MethodDeclaration) left).getNameAsString() + "() и "
                + ((MethodDeclaration) right).getNameAsString() + "()");
    }

    private List<Finding> similarBranches(ProjectModel model, List<BlockStmt> branches, Set<String> seen) {
        List<ScoredPair> pairs = new ArrayList<>();
        for (int first = 0; first < branches.size(); first++) {
            for (int second = first + 1; second < branches.size(); second++) {
                double value = similarity(branches.get(first), branches.get(second), BRANCH_MIN_TOKENS);
                if (value >= BRANCH_SIMILARITY) {
                    pairs.add(new ScoredPair(first, second, value));
                }
            }
        }
        List<Node> anchors = new ArrayList<>();
        for (BlockStmt branch : branches) {
            Statement anchor = anchorStatement(branch);
            anchors.add(anchor);
        }
        return reportPairs(model, seen, pairs, anchors, (left, right) -> "ветви условия (строки "
                + Ast.startLine(left) + " и " + Ast.startLine(right) + ")");
    }

    /**
     * Жадная разметка пар по убыванию похожести: каждый фрагмент попадает не больше чем в одну находку.
     * Так {@code if/else-if/else} с тремя одинаковыми ветвями даёт одну находку, а не три пары.
     */
    private List<Finding> reportPairs(ProjectModel model, Set<String> seen, List<ScoredPair> pairs,
                                      List<Node> anchors, java.util.function.BiFunction<Node, Node, String> describe) {
        pairs.sort(java.util.Comparator.comparingDouble(ScoredPair::score).reversed());
        boolean[] used = new boolean[anchors.size()];
        List<Finding> findings = new ArrayList<>();
        for (ScoredPair pair : pairs) {
            Node left = anchors.get(pair.first());
            Node right = anchors.get(pair.second());
            if (left == null || right == null || used[pair.first()] || used[pair.second()]) {
                continue;
            }
            used[pair.first()] = true;
            used[pair.second()] = true;
            Finding finding = similarFinding(model, seen, right, List.of(left, right),
                    describe.apply(left, right), pair.score());
            if (finding != null) {
                findings.add(finding);
            }
        }
        return findings;
    }

    private record ScoredPair(int first, int second, double score) {
    }

    private Finding similarFinding(ProjectModel model, Set<String> seen, Node at, List<Node> nodes,
                                   String what, double similarity) {
        String key = nodes.stream().map(node -> Ast.nodeId(model, node)).sorted().collect(Collectors.joining("|"));
        if (!seen.add(key)) {
            return null;
        }
        int percent = (int) Math.round(similarity * 100);
        return Findings.of(model, RULE, Severity.MEDIUM,
                "Похожие фрагменты кода",
                "Совпадение " + percent + "%: " + what + ". Почти-копии расходятся при правках так же, "
                        + "как точные: вынесите общую часть в метод с параметром.",
                at, nodes.stream().limit(4).toList(),
                null,
                new Fix("extract-method", "Вынести общую часть в один метод", null, false),
                "Одна реализация вместо нескольких: правка в одном месте");
    }

    /** Ветви if-цепочки: then, все else-if и финальный else. */
    private static List<BlockStmt> branches(IfStmt root) {
        List<BlockStmt> blocks = new ArrayList<>();
        IfStmt current = root;
        while (true) {
            if (current.getThenStmt() instanceof BlockStmt then) {
                blocks.add(then);
            }
            Statement elseStmt = current.getElseStmt().orElse(null);
            if (elseStmt instanceof IfStmt next) {
                current = next;
                continue;
            }
            if (elseStmt instanceof BlockStmt otherwise) {
                blocks.add(otherwise);
            }
            return blocks;
        }
    }

    private static Statement anchorStatement(BlockStmt block) {
        return block.getStatements().isEmpty() ? null : block.getStatements().get(0);
    }

    /** Доля общих токенов (Jaccard) нормализованного текста; 0, если фрагменты малы. */
    private static double similarity(Node left, Node right, int minTokens) {
        Set<String> leftTokens = tokens(Ast.normalize(left));
        Set<String> rightTokens = tokens(Ast.normalize(right));
        if (leftTokens.size() < minTokens || rightTokens.size() < minTokens) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokens(String normalized) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("[^A-Za-z0-9_]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        // Операторы и пунктуация тоже важны: собираем их отдельными токенами.
        for (char symbol : normalized.toCharArray()) {
            if (!Character.isLetterOrDigit(symbol) && !Character.isWhitespace(symbol)) {
                tokens.add(String.valueOf(symbol));
            }
        }
        return tokens;
    }

    private record Chunk(List<Statement> statements) {
    }
}
