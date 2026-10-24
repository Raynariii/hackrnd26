package io.blueprints.core.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Блок-схема тела одного метода или конструктора — проекция AST, без собственного состояния.
 *
 * <p>Обход строит для каждого оператора узлы и возвращает «висячие выходы» ({@link Exit}) — откуда поток
 * идёт дальше. Следующий оператор подключает эти выходы к своему первому узлу. Так ветвления, циклы,
 * break/continue и try собираются без отдельного прохода по графу.
 *
 * <p>Соглашения контракта: id узла = {@code "g" + astId}, синтетические {@code <methodId>:start} / {@code :end}.
 * В {@code meta.covers} — id выражений внутри узла (без вложенных операторов), чтобы находки анализатора,
 * указывающие на выражение (вызов, присваивание), привязывались к блоку.
 */
final class MethodFlowBuilder {

    private record Exit(String from, EdgeKind kind, String label) {
        static Exit next(String from) {
            return new Exit(from, EdgeKind.NEXT, null);
        }
    }

    /** Цель break/continue: цикл или switch, с меткой (для {@code break outer;}). */
    private static final class Frame {
        final String nodeId;
        final String label;
        final boolean loop;
        final List<Exit> breaks = new ArrayList<>();

        Frame(String nodeId, String label, boolean loop) {
            this.nodeId = nodeId;
            this.label = label;
            this.loop = loop;
        }
    }

    private final ProjectModel model;
    private final String file;
    private final String endId;
    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Deque<Frame> frames = new ArrayDeque<>();
    private int edgeCounter;
    /** Метка, стоящая перед следующим циклом/switch (из LabeledStmt). */
    private String pendingLabel;

    private MethodFlowBuilder(ProjectModel model, String file, String methodId) {
        this.model = model;
        this.file = file;
        this.endId = methodId + ":end";
    }

    static Graph build(ProjectModel model, String methodNodeId) {
        if (methodNodeId == null || !methodNodeId.startsWith("g")) {
            throw new IllegalArgumentException("Неизвестный метод: " + methodNodeId);
        }
        Node ast = model.nodeById(methodNodeId.substring(1))
                .orElseThrow(() -> new IllegalArgumentException("Метод не найден: " + methodNodeId));
        if (!(ast instanceof CallableDeclaration<?> callable)) {
            throw new IllegalArgumentException("Узел " + methodNodeId + " не метод и не конструктор");
        }
        String file = model.fileOf(ast).orElse(null);
        MethodFlowBuilder builder = new MethodFlowBuilder(model, file, methodNodeId);
        builder.buildCallable(methodNodeId, callable);
        return new Graph(GraphLevel.METHOD, methodNodeId, builder.nodes, builder.edges);
    }

    private void buildCallable(String methodId, CallableDeclaration<?> callable) {
        String startId = methodId + ":start";
        Map<String, Object> startMeta = new LinkedHashMap<>();
        startMeta.put("name", callable.getNameAsString());
        startMeta.put("covers", List.of(methodId));
        nodes.add(new GraphNode(startId, null, NodeKind.START,
                callable instanceof ConstructorDeclaration ? "Конструктор" : "Метод",
                callable.getDeclarationAsString(true, true, true).strip(), rangeOf(callable), null, List.of(), startMeta));

        BlockStmt body = callable instanceof MethodDeclaration m ? m.getBody().orElse(null)
                : ((ConstructorDeclaration) callable).getBody();
        List<Exit> outs = List.of(Exit.next(startId));
        if (body != null) {
            outs = block(body.getStatements(), outs, null);
        }
        nodes.add(new GraphNode(endId, null, NodeKind.END, "Конец", null, null, null, List.of(), Map.of()));
        connect(outs, endId);
    }

    private List<Exit> block(List<Statement> statements, List<Exit> in, String parent) {
        List<Exit> outs = in;
        for (Statement s : statements) {
            outs = statement(s, outs, parent);
        }
        return outs;
    }

    private List<Exit> body(Statement s, List<Exit> in, String parent) {
        return s instanceof BlockStmt b ? block(b.getStatements(), in, parent) : statement(s, in, parent);
    }

    private List<Exit> statement(Statement s, List<Exit> in, String parent) {
        int nodeMark = nodes.size();
        int edgeMark = edges.size();
        try {
            return dispatch(s, in, parent);
        } catch (RuntimeException e) {
            // Разбор не должен падать на неожиданной конструкции: откатываем частично построенное
            // и показываем оператор «как есть».
            nodes.subList(nodeMark, nodes.size()).clear();
            edges.subList(edgeMark, edges.size()).clear();
            pendingLabel = null;
            return simple(s, NodeKind.RAW, "Код как есть", code(s), in, parent);
        }
    }

    private List<Exit> dispatch(Statement s, List<Exit> in, String parent) {
        if (s instanceof LabeledStmt l) {
            pendingLabel = l.getLabel().asString();
            List<Exit> outs = statement(l.getStatement(), in, parent);
            pendingLabel = null;
            return outs;
        }
        String label = pendingLabel;
        pendingLabel = null;
        if (s instanceof BlockStmt b) {
            return block(b.getStatements(), in, parent);
        }
        if (s instanceof EmptyStmt) {
            return in;
        }
        if (s instanceof ExpressionStmt e) {
            return expression(e, in, parent);
        }
        if (s instanceof ExplicitConstructorInvocationStmt c) {
            return simple(c, NodeKind.CALL, c.isThis() ? "Вызов this(...)" : "Вызов super(...)", code(c), in, parent);
        }
        if (s instanceof AssertStmt a) {
            return simple(a, NodeKind.OPERATION, "Проверка (assert)", code(a), in, parent);
        }
        if (s instanceof ReturnStmt r) {
            String id = add(r, NodeKind.RETURN, "Возврат", code(r), parent, Map.of());
            connect(in, id);
            edge(id, endId, EdgeKind.NEXT, null);
            return List.of();
        }
        if (s instanceof ThrowStmt t) {
            String id = add(t, NodeKind.THROW, "Исключение", code(t), parent, Map.of());
            connect(in, id);
            edge(id, endId, EdgeKind.EXCEPTION, t.getExpression() instanceof ObjectCreationExpr created
                    ? created.getType().getNameAsString() : code(t.getExpression()));
            return List.of();
        }
        if (s instanceof BreakStmt b) {
            return breakStmt(b, in, parent);
        }
        if (s instanceof ContinueStmt c) {
            return continueStmt(c, in, parent);
        }
        if (s instanceof IfStmt i) {
            return ifStmt(i, in, parent);
        }
        if (s instanceof WhileStmt w) {
            return loop(w, "while (" + code(w.getCondition()) + ")", "Цикл (while)", "while", w.getBody(),
                    isTrue(w.getCondition()), label, in, parent);
        }
        if (s instanceof ForStmt f) {
            return loop(f, forHeader(f), "Цикл (for)", "for", f.getBody(),
                    f.getCompare().map(MethodFlowBuilder::isTrue).orElse(true), label, in, parent);
        }
        if (s instanceof ForEachStmt f) {
            String header = "for (" + code(f.getVariable()) + " : " + code(f.getIterable()) + ")";
            return loop(f, header, "Цикл (for each)", "for-each", f.getBody(), false, label, in, parent);
        }
        if (s instanceof DoStmt d) {
            return loop(d, "do … while (" + code(d.getCondition()) + ")", "Цикл (do-while)", "do-while", d.getBody(),
                    isTrue(d.getCondition()), label, in, parent);
        }
        if (s instanceof SwitchStmt sw) {
            return switchStmt(sw, label, in, parent);
        }
        if (s instanceof TryStmt t) {
            return tryStmt(t, in, parent);
        }
        // synchronized, локальные классы/record, yield и прочее — блоком «код как есть».
        return simple(s, NodeKind.RAW, "Код как есть", code(s), in, parent);
    }

    private List<Exit> expression(ExpressionStmt stmt, List<Exit> in, String parent) {
        Expression e = stmt.getExpression();
        if (e instanceof VariableDeclarationExpr) {
            return simple(stmt, NodeKind.VARIABLE, "Переменная", code(e), in, parent);
        }
        if (e instanceof MethodCallExpr || e instanceof ObjectCreationExpr) {
            return simple(stmt, NodeKind.CALL, "Вызов", code(e), in, parent);
        }
        return simple(stmt, NodeKind.OPERATION, "Операция", code(e), in, parent);
    }

    private List<Exit> simple(Node ast, NodeKind kind, String label, String code, List<Exit> in, String parent) {
        String id = add(ast, kind, label, code, parent, Map.of());
        connect(in, id);
        return List.of(Exit.next(id));
    }

    private List<Exit> ifStmt(IfStmt s, List<Exit> in, String parent) {
        String id = add(s, NodeKind.CONDITION, "Условие", code(s.getCondition()), parent, Map.of(),
                rangeOf(s.getCondition()));
        connect(in, id);
        List<Exit> outs = new ArrayList<>(body(s.getThenStmt(), List.of(new Exit(id, EdgeKind.TRUE, "Да")), id));
        Exit no = new Exit(id, EdgeKind.FALSE, "Нет");
        if (s.getElseStmt().isPresent()) {
            outs.addAll(body(s.getElseStmt().get(), List.of(no), id));
        } else {
            outs.add(no);
        }
        return outs;
    }

    private List<Exit> loop(Statement s, String header, String label, String loopKind, Statement loopBody,
                            boolean infinite, String jumpLabel, List<Exit> in, String parent) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("loopKind", loopKind);
        if (infinite) {
            meta.put("infinite", true);
        }
        if (jumpLabel != null) {
            meta.put("jumpLabel", jumpLabel);
        }
        String id = add(s, NodeKind.LOOP, label, header, parent, meta);
        connect(in, id);
        Frame frame = new Frame(id, jumpLabel, true);
        frames.push(frame);
        List<Exit> bodyOuts;
        try {
            bodyOuts = body(loopBody, List.of(new Exit(id, EdgeKind.LOOP_BODY, null)), id);
        } finally {
            frames.pop();
        }
        for (Exit exit : bodyOuts) {
            // Обычный конец итерации — loopBack; ветка «Нет» условия в конце тела сохраняет свою подпись.
            // Выход вложенного цикла тоже возвращает к заголовку — это обратное ребро.
            if (exit.kind() == EdgeKind.NEXT || exit.kind() == EdgeKind.LOOP_BODY
                    || exit.kind() == EdgeKind.LOOP_EXIT) {
                edge(exit.from(), id, EdgeKind.LOOP_BACK, null);
            } else {
                edge(exit.from(), id, exit.kind(), exit.label());
            }
        }
        List<Exit> outs = new ArrayList<>();
        if (!infinite) {
            outs.add(new Exit(id, EdgeKind.LOOP_EXIT, null)); // из while(true)/for(;;) выходят только через break
        }
        outs.addAll(frame.breaks);
        return outs;
    }

    private List<Exit> breakStmt(BreakStmt b, List<Exit> in, String parent) {
        String target = b.getLabel().map(l -> l.asString()).orElse(null);
        String id = add(b, NodeKind.BREAK, "Выход (break)", code(b), parent, Map.of());
        connect(in, id);
        Frame frame = findFrame(target, false);
        if (frame != null) {
            frame.breaks.add(new Exit(id, EdgeKind.BREAK, null));
            return List.of();
        }
        return List.of(new Exit(id, EdgeKind.BREAK, null));
    }

    private List<Exit> continueStmt(ContinueStmt c, List<Exit> in, String parent) {
        String target = c.getLabel().map(l -> l.asString()).orElse(null);
        String id = add(c, NodeKind.CONTINUE, "Следующая итерация", code(c), parent, Map.of());
        connect(in, id);
        Frame frame = findFrame(target, true);
        if (frame != null) {
            edge(id, frame.nodeId, EdgeKind.CONTINUE, null);
            return List.of();
        }
        return List.of(new Exit(id, EdgeKind.CONTINUE, null));
    }

    /** Ближайший подходящий фрейм: по метке, иначе — ближайший цикл (continue) или цикл/switch (break). */
    private Frame findFrame(String label, boolean loopOnly) {
        for (Frame f : frames) {
            if (label != null ? label.equals(f.label) : (!loopOnly || f.loop)) {
                return f;
            }
        }
        return null;
    }

    private List<Exit> switchStmt(SwitchStmt s, String jumpLabel, List<Exit> in, String parent) {
        String id = add(s, NodeKind.SWITCH, "Выбор (switch)", code(s.getSelector()), parent, Map.of(),
                rangeOf(s.getSelector()));
        connect(in, id);
        Frame frame = new Frame(id, jumpLabel, false);
        frames.push(frame);
        List<Exit> outs = new ArrayList<>();
        boolean hasDefault = false;
        try {
            List<Exit> fallthrough = List.of();
            for (SwitchEntry entry : s.getEntries()) {
                boolean isDefault = entry.getLabels().isEmpty();
                hasDefault |= isDefault;
                String caseLabel = isDefault ? "default"
                        : entry.getLabels().stream().map(MethodFlowBuilder::code).collect(Collectors.joining(", "));
                String caseCode = isDefault ? "default" : "case " + caseLabel;
                String caseId = add(entry, NodeKind.CASE, isDefault ? "Иначе (default)" : "Вариант (case)",
                        caseCode, id, Map.of(), rangeOf(entry));
                edge(id, caseId, EdgeKind.CASE, caseLabel);
                connect(fallthrough, caseId);
                List<Exit> caseOuts = block(entry.getStatements(), List.of(Exit.next(caseId)), caseId);
                if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
                    fallthrough = caseOuts;   // старый switch: без break выполнение проваливается в следующий case
                } else {
                    outs.addAll(caseOuts);    // case X -> ...: провала нет
                    fallthrough = List.of();
                }
            }
            outs.addAll(fallthrough);
        } finally {
            frames.pop();
        }
        outs.addAll(frame.breaks);
        if (!hasDefault) {
            outs.add(new Exit(id, EdgeKind.NEXT, "нет совпадений"));
        }
        return outs;
    }

    /** catch и finally — части оператора try: их {@code parent} = try, тела — с parent = catch / finally. */
    private List<Exit> tryStmt(TryStmt t, List<Exit> in, String parent) {
        String header = t.getResources().isEmpty() ? "try"
                : "try (" + t.getResources().stream().map(MethodFlowBuilder::code).collect(Collectors.joining("; ")) + ")";
        String id = add(t, NodeKind.TRY, "Попытка (try)", header, parent, Map.of());
        connect(in, id);
        List<Exit> outs = new ArrayList<>(block(t.getTryBlock().getStatements(), List.of(Exit.next(id)), id));
        for (CatchClause c : t.getCatchClauses()) {
            String type = c.getParameter().getType().toString();
            String catchId = add(c, NodeKind.CATCH, "Обработка (catch)", "catch (" + code(c.getParameter()) + ")",
                    id, Map.of());
            edge(id, catchId, EdgeKind.EXCEPTION, type);
            outs.addAll(block(c.getBody().getStatements(), List.of(Exit.next(catchId)), catchId));
        }
        if (t.getFinallyBlock().isPresent()) {
            BlockStmt fin = t.getFinallyBlock().get();
            String finId = add(fin, NodeKind.FINALLY, "Всегда (finally)", "finally", id, Map.of());
            for (Exit exit : outs) {
                edge(exit.from(), finId, EdgeKind.FINALLY, exit.label());
            }
            return block(fin.getStatements(), List.of(Exit.next(finId)), finId);
        }
        return outs;
    }

    // ---------------------------------------------------------------- узлы и рёбра

    private String add(Node ast, NodeKind kind, String label, String code, String parent, Map<String, Object> meta) {
        return add(ast, kind, label, code, parent, meta, rangeOf(ast));
    }

    private String add(Node ast, NodeKind kind, String label, String code, String parent, Map<String, Object> meta,
                       SourceRange range) {
        String id = "g" + model.idOf(ast);
        Map<String, Object> fullMeta = new LinkedHashMap<>(meta);
        List<String> covers = covers(ast);
        if (!covers.isEmpty()) {
            fullMeta.put("covers", covers);
        }
        nodes.add(new GraphNode(id, model.idOf(ast), kind, label, code, range, parent, List.of(), fullMeta));
        return id;
    }

    /** Id подузлов AST, принадлежащих этому блоку: всё внутри, кроме вложенных операторов (у них свои блоки). */
    private List<String> covers(Node ast) {
        List<String> ids = new ArrayList<>();
        for (Node child : ast.getChildNodes()) {
            collect(child, ids);
        }
        return ids;
    }

    private void collect(Node node, List<String> ids) {
        if (node instanceof Statement || node instanceof SwitchEntry || node instanceof CatchClause) {
            return;
        }
        String astId = model.idOf(node);
        if (astId != null && (node instanceof Expression || node instanceof VariableDeclarator)) {
            ids.add("g" + astId);
        }
        for (Node child : node.getChildNodes()) {
            collect(child, ids);
        }
    }

    private void connect(List<Exit> exits, String to) {
        for (Exit exit : exits) {
            edge(exit.from(), to, exit.kind(), exit.label());
        }
    }

    private void edge(String from, String to, EdgeKind kind, String label) {
        edges.add(new GraphEdge("e" + (++edgeCounter), from, to, kind, label));
    }

    private SourceRange rangeOf(Node ast) {
        return ast.getRange()
                .map(r -> new SourceRange(file, r.begin.line, r.end.line, r.begin.column, r.end.column))
                .orElse(null);
    }

    /** Код узла без комментариев: в подписи блока они только мешают. AST проекта не трогаем — работаем с копией. */
    private static String code(Node node) {
        Node copy = node.clone();
        copy.getAllContainedComments().forEach(Comment::remove);
        copy.removeComment();
        String text = copy.toString().strip();
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String forHeader(ForStmt f) {
        String init = f.getInitialization().stream().map(MethodFlowBuilder::code).collect(Collectors.joining(", "));
        String cond = f.getCompare().map(MethodFlowBuilder::code).orElse("");
        String update = f.getUpdate().stream().map(MethodFlowBuilder::code).collect(Collectors.joining(", "));
        if (init.isEmpty() && cond.isEmpty() && update.isEmpty()) {
            return "for (;;)";
        }
        return "for (" + init + "; " + cond + "; " + update + ")";
    }

    private static boolean isTrue(Expression e) {
        return e instanceof BooleanLiteralExpr b && b.getValue();
    }
}
