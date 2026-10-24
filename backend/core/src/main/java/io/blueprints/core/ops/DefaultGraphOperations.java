package io.blueprints.core.ops;

import com.github.javaparser.Position;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import io.blueprints.core.graph.Members;
import io.blueprints.core.graph.NodeKind;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Операции графа. Граф — проекция AST, поэтому операция находит узлы AST по id графа ({@code "g" + astId},
 * {@code <methodId>:start} / {@code :end}) и меняет текст файла <b>ровно в границах этих узлов</b>, с отступом
 * места вставки; затем файл заново разбирается и его AST заменяет старый в модели. Нетронутый код остаётся
 * байт-в-байт, а вставленный выровнен по соседям — дифф для пользователя читаемый. (Правка самого AST с печатью
 * через LexicalPreservingPrinter сбивает отступы соседних строк и ломает метки case — см. G6 в LEAD.md.)
 *
 * <p>Цели: блоки блок-схемы (операторы, case, catch, finally, {@code :start}); члены обзорного графа (методы,
 * конструкторы, поля, вложенные типы); id выражения (например из {@code Finding.nodeIds}) — тогда операция
 * применяется к содержащему его оператору.
 *
 * <ul>
 *   <li>{@code editNode} — у обычного блока {@code code} заменяет оператор целиком (можно несколько операторов);
 *       у условия, цикла, switch — только заголовок ({@code x > 0}, {@code int i = 0; i < n; i++}), тело
 *       сохраняется; полная конструкция того же вида ({@code if (...) {...}}) заменяет её целиком;
 *       у case — метки, у catch — параметр, у try — ресурсы; у члена класса — объявление.</li>
 *   <li>{@code insertAfter} — новый оператор после блока. После {@code :start}, case, catch, finally — в начало
 *       их тела; после цикла/условия/try/switch — после всей конструкции. Если задан {@code kind}, код может быть
 *       сокращённым (палитра): {@code condition} + {@code x > 0} → {@code if (x > 0) {}}, {@code loop} +
 *       {@code int i = 0; i < n; i++} → for, {@code return} + {@code x} → {@code return x;}.</li>
 *   <li>{@code delete} — удалить блок (контейнер — вместе с телом); {@code nodeIds} — несколько за раз.</li>
 *   <li>{@code wrapIf} / {@code wrapLoop} — обернуть подряд идущие операторы ({@code nodeIds}) в if / цикл;
 *       {@code code} — условие или заголовок ({@code x > 0}; {@code int i = 0; i < n; i++}, {@code x : xs}, {@code i < n}).</li>
 *   <li>{@code move} — перенести подряд идущие операторы (или члены класса) в место {@code targetId} по правилам
 *       insertAfter.</li>
 *   <li>{@code extractMethod} — вынести подряд идущие операторы в private-метод {@code name} (параметры и
 *       возвращаемая переменная определяются по коду).</li>
 * </ul>
 * Ошибка → {@link UnsupportedGraphOpException} с текстом для пользователя, модель при этом не меняется.
 */
public final class DefaultGraphOperations implements GraphOperations {

    private static final Pattern WHILE_HEADER = Pattern.compile("(?s).*?\\bwhile\\s*\\((.*)\\)\\s*;?\\s*");
    private static final Pattern SWITCH_HEADER = Pattern.compile("(?s)\\s*switch\\s*\\((.*)\\)\\s*");
    private static final Pattern IF_HEADER = Pattern.compile("(?s)\\s*if\\s*\\((.*)\\)\\s*");
    private static final Pattern TRY_HEADER = Pattern.compile("(?s)\\s*try\\s*(\\(.*\\))?\\s*");
    private static final Pattern CATCH_HEADER = Pattern.compile("(?s)\\s*catch\\s*\\((.*)\\)\\s*");

    private final Snippets snippets = new Snippets();

    @Override
    public void apply(ProjectModel model, GraphOp op) {
        if (op == null || op.op() == null) {
            throw new UnsupportedGraphOpException("Не указана операция");
        }
        try {
            switch (op.op()) {
                case "editNode" -> edit(model, op);
                case "insertAfter" -> insertAfter(model, op);
                case "delete" -> delete(model, op);
                case "wrapIf" -> wrap(model, op, true);
                case "wrapLoop" -> wrap(model, op, false);
                case "move" -> move(model, op);
                case "extractMethod" -> extractMethod(model, op);
                default -> throw new UnsupportedGraphOpException("Неизвестная операция: " + op.op());
            }
        } catch (UnsupportedGraphOpException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UnsupportedGraphOpException("Не удалось применить '" + op.op() + "': " + e.getMessage());
        }
    }

    /** Файл, в котором правим, и его текст. */
    private record Change(String path, SourceText text) {
    }

    private static Change change(ProjectModel model, Node node) {
        String path = model.fileOf(node).orElseThrow(() -> new UnsupportedGraphOpException("Узел вне файлов проекта"));
        CompilationUnit cu = model.units().get(path);
        return new Change(path, new SourceText(LexicalPreservingPrinter.print(cu)));
    }

    /** Применить правки, разобрать результат и подменить файл в модели. Код не разбирается — модель не трогаем. */
    private static void commit(ProjectModel model, Change change) {
        String updated = change.text().apply();
        ProjectModel parsed = new JavaProjectParser().parse(List.of(new SourceFile(change.path(), updated)));
        if (parsed.hasErrors() || !parsed.units().containsKey(change.path())) {
            String reason = parsed.problems().isEmpty() ? "" : ": " + parsed.problems().get(0).message()
                    .lines().findFirst().orElse("");
            throw new UnsupportedGraphOpException("После операции код не разбирается" + reason);
        }
        model.replaceUnit(change.path(), parsed.units().get(change.path()));
    }

    // ---------------------------------------------------------------- editNode

    private void edit(ProjectModel model, GraphOp op) {
        Target target = resolve(model, op.nodeId());
        String code = requireCode(op);
        if (target.anchor() != Anchor.NODE) {
            throw new UnsupportedGraphOpException("Начало и конец блок-схемы не редактируются");
        }
        Node node = target.node();
        Change change = change(model, node);
        SourceText st = change.text();
        if (node instanceof BodyDeclaration<?> member) {
            snippets.member(code);
            replaceNode(st, member, code);
        } else if (node instanceof SwitchEntry entry) {
            st.replace(st.begin(entry), headerEnd(st, entry), snippets.caseHeader(code));
        } else if (node instanceof CatchClause c) {
            Matcher m = CATCH_HEADER.matcher(code);
            String inner = (m.matches() ? m.group(1) : code).strip();
            snippets.catchParameter(inner);
            st.replace(st.begin(c.getParameter()), st.end(c.getParameter()), inner);
        } else if (isFinally(node)) {
            throw new UnsupportedGraphOpException("У finally нет заголовка: правьте операторы внутри");
        } else if (node instanceof Statement s) {
            editStatement(st, s, code);
        } else {
            throw new UnsupportedGraphOpException("Этот узел нельзя редактировать");
        }
        commit(model, change);
    }

    private void editStatement(SourceText st, Statement s, String code) {
        if (s instanceof IfStmt i) {
            editHeader(st, s, i.getCondition(), code, IF_HEADER, IfStmt.class, "Ожидалось условие, например: x > 0");
        } else if (s instanceof WhileStmt w) {
            editHeader(st, s, w.getCondition(), code, WHILE_HEADER, WhileStmt.class,
                    "Ожидалось условие цикла, например: i < n");
        } else if (s instanceof DoStmt d) {
            editHeader(st, s, d.getCondition(), code, WHILE_HEADER, DoStmt.class,
                    "Ожидалось условие цикла, например: i < n");
        } else if (s instanceof SwitchStmt sw) {
            editHeader(st, s, sw.getSelector(), code, SWITCH_HEADER, SwitchStmt.class,
                    "Ожидалось выражение switch, например: day");
        } else if (s instanceof ForStmt || s instanceof ForEachStmt) {
            editForHeader(st, s, code);
        } else if (s instanceof TryStmt t) {
            editTryHeader(st, t, code);
        } else {
            replaceStatement(st, s, snippets.statements(code));
        }
    }

    /**
     * Заголовок-выражение (условие, селектор): выражение или {@code if (x)} меняют только его; полная конструкция
     * того же вида заменяет оператор целиком. Иное — ошибка, чтобы опечатка не стёрла тело.
     */
    private void editHeader(SourceText st, Statement s, Node expression, String code, Pattern header,
                            Class<? extends Statement> kind, String hint) {
        Matcher m = header.matcher(code);
        String expr = (m.matches() ? m.group(1) : code).strip();
        if (snippets.isExpression(expr)) {
            st.replace(st.begin(expression), st.end(expression), expr);
        } else if (snippets.isSingle(code, kind)) {
            replaceNode(st, s, code);
        } else {
            throw new UnsupportedGraphOpException(hint);
        }
    }

    /** Заголовок for / for-each: всё от начала оператора до тела. Вид цикла может смениться (for ↔ for-each). */
    private void editForHeader(SourceText st, Statement loop, String code) {
        String c = code.strip();
        if (c.endsWith("}") && (snippets.isSingle(c, ForStmt.class) || snippets.isSingle(c, ForEachStmt.class))) {
            replaceNode(st, loop, c);
            return;
        }
        String header = c.startsWith("for") ? c : "for (" + c + ")";
        snippets.loopHeader(header);
        Statement body = loop instanceof ForStmt f ? f.getBody() : ((ForEachStmt) loop).getBody();
        st.replace(st.begin(loop), st.begin(body), header + " ");
    }

    private void editTryHeader(SourceText st, TryStmt t, String code) {
        Matcher m = TRY_HEADER.matcher(code);
        if (!m.matches()) {
            if (!snippets.isSingle(code, TryStmt.class)) {
                throw new UnsupportedGraphOpException("Ожидалось: try или try (ресурсы)");
            }
            replaceNode(st, t, code);
            return;
        }
        String resources = m.group(1);
        if (resources == null && t.getCatchClauses().isEmpty() && t.getFinallyBlock().isEmpty()) {
            throw new UnsupportedGraphOpException("У try должен остаться catch, finally или ресурсы");
        }
        if (resources != null) {
            snippets.tryResources(resources);
        }
        st.replace(st.begin(t), st.begin(t.getTryBlock()), resources == null ? "try " : "try " + resources.strip() + " ");
    }

    /** Заменить оператор кодом; одиночное тело (if (x) a();) при нескольких операторах оборачивается в блок. */
    private void replaceStatement(SourceText st, Statement s, String code) {
        Node parent = s.getParentNode().orElse(null);
        boolean single = snippets.isSingle(code, Statement.class);
        if (parent instanceof SwitchEntry e && isArrowSingle(e)) {
            int end = st.skipSemicolon(st.end(s));
            boolean inline = snippets.isSingle(code, ExpressionStmt.class) || snippets.isSingle(code, ThrowStmt.class);
            st.replace(st.begin(s), end, inline ? code : braced(st, st.indentAt(st.begin(e)), List.of(code)));
        } else if (statementList(s) == null && !(parent instanceof LabeledStmt) && !single) {
            st.replace(st.begin(s), st.end(s), braced(st, st.indentAt(st.begin(parent)), List.of(code)));
        } else {
            replaceNode(st, s, code);
        }
    }

    private static void replaceNode(SourceText st, Node node, String code) {
        int begin = st.begin(node);
        st.replace(begin, st.end(node), st.reindent(code, st.indentAt(begin)));
    }

    // ---------------------------------------------------------------- insertAfter

    private void insertAfter(ProjectModel model, GraphOp op) {
        Target target = resolve(model, op.nodeId());
        Node node = target.node();
        if (target.anchor() == Anchor.END) {
            throw new UnsupportedGraphOpException("После конца метода вставлять нельзя");
        }
        Change change = change(model, node);
        SourceText st = change.text();
        if (target.anchor() == Anchor.NODE && node instanceof BodyDeclaration<?> member) {
            String code = requireCode(op);
            boolean field = snippets.member(code);
            insertLineAfter(st, member, code, field ? "" : st.newline());
        } else {
            insertStatements(st, target, snippets.statements(template(op.kind(), op.code() == null ? "" : op.code())));
        }
        commit(model, change);
    }

    /** Вставить операторы в место, заданное целью: начало метода, case, catch, finally или после оператора. */
    private void insertStatements(SourceText st, Target target, String code) {
        Node node = target.node();
        if (target.anchor() == Anchor.START) {
            insertAtStart(st, node, code);
        } else if (node instanceof SwitchEntry entry) {
            insertIntoCase(st, entry, code);
        } else if (node instanceof CatchClause c) {
            insertAtBlockStart(st, c.getBody(), code);
        } else if (isFinally(node)) {
            insertAtBlockStart(st, (BlockStmt) node, code);
        } else if (node instanceof Statement s) {
            insertAfterStatement(st, outerLabeled(s), code);
        } else {
            throw new UnsupportedGraphOpException("После этого узла вставлять нельзя");
        }
    }

    private void insertAtStart(SourceText st, Node callable, String code) {
        BlockStmt body = Members.body(callable)
                .orElseThrow(() -> new UnsupportedGraphOpException("У абстрактного метода нет тела"));
        NodeList<Statement> stmts = body.getStatements();
        // this(...) / super(...) обязаны быть первым оператором конструктора.
        if (!stmts.isEmpty() && stmts.get(0).isExplicitConstructorInvocationStmt()) {
            insertAfterStatement(st, stmts.get(0), code);
        } else {
            insertAtBlockStart(st, body, code);
        }
    }

    private void insertIntoCase(SourceText st, SwitchEntry entry, String code) {
        NodeList<Statement> stmts = entry.getStatements();
        if (entry.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            if (!stmts.isEmpty()) {
                insertBefore(st, stmts.get(0), code);
            } else {
                int colon = st.find(':', headerEnd(st, entry));
                String inner = SourceText.deeper(st.indentAt(st.begin(entry)));
                st.insert(colon + 1, st.newline() + inner + st.reindent(code, inner));
            }
        } else if (stmts.size() == 1 && stmts.get(0) instanceof BlockStmt block) {
            insertAtBlockStart(st, block, code);
        } else {
            // case X -> expr; → case X -> { вставка; expr; }
            Statement only = stmts.get(0);
            int end = st.skipSemicolon(st.end(only));
            String old = st.slice(st.begin(only), end);
            st.replace(st.begin(only), end, braced(st, st.indentAt(st.begin(entry)),
                    List.of(code, old.endsWith(";") ? old : old + ";")));
        }
    }

    private void insertAtBlockStart(SourceText st, BlockStmt block, String code) {
        if (!block.getStatements().isEmpty()) {
            insertBefore(st, block.getStatement(0), code);
            return;
        }
        int open = st.begin(block);
        int close = st.end(block) - 1;
        String outer = st.indentAt(open);
        String inner = SourceText.deeper(outer);
        if (st.slice(open + 1, close).isBlank()) {
            st.replace(open + 1, close, st.newline() + inner + st.reindent(code, inner) + st.newline() + outer);
        } else { // в пустом блоке есть комментарий — оставляем его после вставки
            st.insert(open + 1, st.newline() + inner + st.reindent(code, inner));
        }
    }

    /** Перед оператором (и его комментарием сверху), с его отступом. */
    private static void insertBefore(SourceText st, Statement first, String code) {
        int at = leadingStart(st, first);
        String indent = st.indentAt(at);
        st.insert(at, st.reindent(code, indent) + st.newline() + indent);
    }

    private void insertAfterStatement(SourceText st, Statement anchor, String code) {
        Node parent = anchor.getParentNode().orElseThrow();
        if (statementList(anchor) != null) {
            insertLineAfter(st, anchor, code, "");
        } else if (parent instanceof SwitchEntry e && isArrowSingle(e)) {
            int end = st.skipSemicolon(st.end(anchor));
            String old = st.slice(st.begin(anchor), end);
            st.replace(st.begin(anchor), end, braced(st, st.indentAt(st.begin(e)),
                    List.of(old.endsWith(";") ? old : old + ";", code)));
        } else {
            // Тело без фигурных скобок (if (x) a();) — оборачиваем в блок, чтобы вставка осталась внутри ветки.
            st.replace(st.begin(anchor), st.end(anchor),
                    braced(st, st.indentAt(st.begin(parent)), List.of(st.source(anchor), code)));
        }
    }

    /** Новая строка после узла с его отступом (после хвостового комментария, если он есть). */
    private static void insertLineAfter(SourceText st, Node anchor, String code, String gap) {
        int begin = st.begin(anchor);
        int at = st.end(anchor);
        String indent = st.blankBefore(begin) ? st.indentAt(begin) : SourceText.deeper(st.indentAt(begin));
        if (st.blankOrCommentAfter(at)) {
            st.insert(st.lineEnd(at), st.newline() + gap + indent + st.reindent(code, indent));
        } else { // за узлом на той же строке есть код: «case 1: a(); break;»
            st.insert(at, " " + st.reindent(code, indent));
        }
    }

    /** {@code {} } с операторами внутри на уровень глубже {@code outer}. */
    private static String braced(SourceText st, String outer, List<String> statements) {
        String inner = SourceText.deeper(outer);
        StringBuilder out = new StringBuilder("{");
        for (String s : statements) {
            out.append(st.newline()).append(inner).append(st.reindent(s, inner));
        }
        return out.append(st.newline()).append(outer).append('}').toString();
    }

    /**
     * Шаблоны палитры: по виду блока сокращённый код достраивается до оператора. Полный оператор
     * ({@code if (...) {...}}, {@code return x;}) проходит без изменений.
     */
    static String template(NodeKind kind, String code) {
        String c = code.strip();
        if (kind == null) {
            return c;
        }
        return switch (kind) {
            case CONDITION -> c.startsWith("if") && c.endsWith("}") ? c : "if (" + orTrue(stripHeader(c, "if")) + ") {\n}";
            case LOOP -> loopTemplate(c);
            case SWITCH -> c.endsWith("}") ? c : "switch (" + stripHeader(c, "switch") + ") {\n}";
            case RETURN -> c.startsWith("return") ? c : c.isEmpty() ? "return;" : "return " + c + ";";
            case THROW -> c.startsWith("throw") ? c : "throw " + (c.isEmpty() ? "new RuntimeException()" : c) + ";";
            case BREAK -> c.isEmpty() ? "break;" : c;
            case CONTINUE -> c.isEmpty() ? "continue;" : c;
            case TRY -> c.startsWith("try") && c.endsWith("}") ? c
                    : (c.isEmpty() ? "try" : c) + " {\n} catch (Exception e) {\n}";
            default -> c;
        };
    }

    private static String loopTemplate(String c) {
        if (c.endsWith("}") || c.startsWith("do")) {
            return c;
        }
        if (c.startsWith("for") || c.startsWith("while")) {
            return c + " {\n}";
        }
        if (c.contains(";") || c.contains(":")) {
            return "for (" + c + ") {\n}";
        }
        return "while (" + orTrue(c) + ") {\n}";
    }

    /** {@code if (x > 0)} → {@code x > 0}: в палитре можно ввести и заголовок, и одно условие. */
    private static String stripHeader(String c, String keyword) {
        if (c.startsWith(keyword) && c.endsWith(")")) {
            int open = c.indexOf('(');
            if (open > 0) {
                return c.substring(open + 1, c.length() - 1).strip();
            }
        }
        return c;
    }

    private static String orTrue(String c) {
        return c.isEmpty() ? "true" : c;
    }

    // ---------------------------------------------------------------- delete

    private void delete(ProjectModel model, GraphOp op) {
        List<String> ids = op.nodeId() != null ? List.of(op.nodeId())
                : op.nodeIds() != null ? op.nodeIds() : List.of();
        if (ids.isEmpty()) {
            throw new UnsupportedGraphOpException("Не указан узел для удаления");
        }
        List<Node> nodes = new ArrayList<>();
        for (String id : ids) {
            Target target = resolve(model, id);
            if (target.anchor() != Anchor.NODE) {
                throw new UnsupportedGraphOpException("Начало и конец блок-схемы удалить нельзя");
            }
            nodes.add(target.node());
        }
        Change change = change(model, nodes.get(0));
        for (Node node : nodes) {
            if (!model.fileOf(node).orElse("").equals(change.path())) {
                throw new UnsupportedGraphOpException("Удалять за раз можно узлы только из одного файла");
            }
        }
        requireValidTries(nodes);
        for (Node node : nodes) {
            deleteNode(change.text(), node);
        }
        commit(model, change);
    }

    private void deleteNode(SourceText st, Node node) {
        if (node instanceof BodyDeclaration<?> member) {
            removeLines(st, member, st.end(member));
        } else if (node instanceof SwitchEntry entry) {
            int end = st.end(entry);
            removeLines(st, entry, isArrowSingle(entry) ? st.skipSemicolon(end) : end);
        } else if (node instanceof CatchClause c) {
            TryStmt t = (TryStmt) c.getParentNode().orElseThrow();
            int i = indexOf(t.getCatchClauses(), c);
            Node prev = i == 0 ? t.getTryBlock() : t.getCatchClauses().get(i - 1);
            st.replace(st.end(prev), st.end(c), "");
        } else if (isFinally(node)) {
            TryStmt t = (TryStmt) node.getParentNode().orElseThrow();
            Node prev = t.getCatchClauses().isEmpty() ? t.getTryBlock() : t.getCatchClauses().getLast().orElseThrow();
            st.replace(st.end(prev), st.end(node), "");
        } else if (node instanceof Statement s) {
            deleteStatement(st, outerLabeled(s));
        } else {
            throw new UnsupportedGraphOpException("Этот узел нельзя удалить");
        }
    }

    private static void deleteStatement(SourceText st, Statement s) {
        if (statementList(s) != null) {
            removeLines(st, s, st.end(s));
            return;
        }
        Node parent = s.getParentNode().orElseThrow();
        if (parent instanceof IfStmt i && i.getElseStmt().filter(e -> e == s).isPresent()) {
            st.replace(st.end(i.getThenStmt()), st.end(s), "");
        } else if (parent instanceof SwitchEntry e && isArrowSingle(e)) {
            st.replace(st.begin(s), st.skipSemicolon(st.end(s)), "{ }");
        } else {
            st.replace(st.begin(s), st.end(s), "{ }"); // тело цикла / ветка if без скобок → пустой блок
        }
    }

    /** Удалить узел (с комментарием над ним); если он занимает строки целиком — вместе со строками. */
    private static void removeLines(SourceText st, Node node, int end) {
        removeSpan(st, leadingStart(st, node), end);
    }

    private static void removeSpan(SourceText st, int start, int end) {
        if (st.blankBefore(start) && st.blankOrCommentAfter(end)) {
            st.replace(st.lineStart(start), st.nextLine(end), "");
        } else {
            int to = end;
            while (to < st.text().length() && st.text().charAt(to) == ' ') {
                to++;
            }
            st.replace(start, to, "");
        }
    }

    /** Начало узла или его комментария, если комментарий стоит отдельно над ним (javadoc, // пояснение). */
    private static int leadingStart(SourceText st, Node node) {
        int begin = st.begin(node);
        Optional<Comment> comment = node.getComment();
        if (comment.isPresent() && comment.get().getBegin().isPresent()) {
            int c = st.begin(comment.get());
            if (c < begin && st.blankBefore(c)) {
                return c;
            }
        }
        return begin;
    }

    /** После удаления у каждого затронутого try должен остаться catch, finally или ресурсы. */
    private static void requireValidTries(List<Node> deleted) {
        Set<Node> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        gone.addAll(deleted);
        Set<TryStmt> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node n : deleted) {
            if (n instanceof CatchClause || isFinally(n)) {
                touched.add((TryStmt) n.getParentNode().orElseThrow());
            }
        }
        for (TryStmt t : touched) {
            if (gone.contains(t)) {
                continue;
            }
            boolean hasCatch = t.getCatchClauses().stream().anyMatch(c -> !gone.contains(c));
            boolean hasFinally = t.getFinallyBlock().filter(f -> !gone.contains(f)).isPresent();
            if (!hasCatch && !hasFinally && t.getResources().isEmpty()) {
                throw new UnsupportedGraphOpException("У try должен остаться catch, finally или ресурсы");
            }
        }
    }

    // ---------------------------------------------------------------- wrapIf / wrapLoop

    /** Обернуть подряд идущие операторы ({@code nodeIds} или {@code nodeId}) в if или цикл; {@code code} — заголовок. */
    private void wrap(ProjectModel model, GraphOp op, boolean isIf) {
        List<Node> sel = selection(model, ids(op), false);
        String header = isIf ? ifHeader(requireCode(op)) : loopHeader(requireCode(op));
        requireNoEscapingDeclarations(sel);
        Change change = change(model, sel.get(0));
        SourceText st = change.text();
        Node first = sel.get(0);
        Node last = sel.get(sel.size() - 1);
        String indent = st.indentAt(st.begin(first));
        String inner = SourceText.deeper(indent);
        st.replace(leadingStart(st, first), st.end(last), header + " {" + st.newline() + inner
                + st.reindent(blockText(st, first, last), inner) + st.newline() + indent + "}");
        commit(model, change);
    }

    /** Переменная, объявленная в выделении и нужная после него, после обёртки окажется вне области видимости. */
    private static void requireNoEscapingDeclarations(List<Node> sel) {
        CallableDeclaration<?> callable = sel.get(0).findAncestor(CallableDeclaration.class).orElse(null);
        if (callable == null) {
            return;
        }
        Set<String> after = namesAfter(callable, sel.get(sel.size() - 1));
        List<String> escaping = new ArrayList<>();
        for (Node n : sel) {
            if (n instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr v) {
                v.getVariables().stream().map(VariableDeclarator::getNameAsString).filter(after::contains)
                        .forEach(escaping::add);
            }
        }
        if (!escaping.isEmpty()) {
            throw new UnsupportedGraphOpException("Переменная " + String.join(", ", escaping)
                    + " нужна после блока — после обёртки она станет недоступна. Выделите и её использования");
        }
    }

    private String ifHeader(String code) {
        String cond = stripHeader(code.strip(), "if");
        if (!snippets.isExpression(cond)) {
            throw new UnsupportedGraphOpException("Ожидалось условие, например: x > 0");
        }
        return "if (" + cond + ")";
    }

    private String loopHeader(String code) {
        String c = code.strip();
        String header = c.startsWith("for") || c.startsWith("while") ? c
                : c.contains(";") || c.contains(":") ? "for (" + c + ")" : "while (" + c + ")";
        if (!snippets.isSingle(header + " {}", ForStmt.class) && !snippets.isSingle(header + " {}", ForEachStmt.class)
                && !snippets.isSingle(header + " {}", WhileStmt.class)) {
            throw new UnsupportedGraphOpException("Ожидался заголовок цикла, например: int i = 0; i < n; i++");
        }
        return header;
    }

    // ---------------------------------------------------------------- move

    /**
     * Переместить подряд идущие операторы ({@code nodeIds} или {@code nodeId}) в место {@code targetId} — по тем же
     * правилам, что insertAfter. Члены класса перемещаются после другого члена.
     */
    private void move(ProjectModel model, GraphOp op) {
        if (op.targetId() == null || op.targetId().isBlank()) {
            throw new UnsupportedGraphOpException("Не указано, куда перемещать (targetId)");
        }
        List<Node> sel = selection(model, ids(op), true);
        Target target = resolve(model, op.targetId());
        if (target.anchor() == Anchor.END) {
            throw new UnsupportedGraphOpException("После конца метода вставлять нельзя");
        }
        for (Node n : sel) {
            if (n == target.node() || n.isAncestorOf(target.node())) {
                throw new UnsupportedGraphOpException("Нельзя переместить блок внутрь самого себя");
            }
        }
        Change change = change(model, sel.get(0));
        if (!model.fileOf(target.node()).orElse("").equals(change.path())) {
            throw new UnsupportedGraphOpException("Перемещать можно только внутри одного файла");
        }
        SourceText st = change.text();
        Node first = sel.get(0);
        Node last = sel.get(sel.size() - 1);
        String text = blockText(st, first, last);
        boolean members = first instanceof BodyDeclaration<?>;
        if (members != (target.anchor() == Anchor.NODE && target.node() instanceof BodyDeclaration<?>)) {
            throw new UnsupportedGraphOpException(members
                    ? "Член класса перемещается только после другого члена класса"
                    : "Оператор перемещается только внутрь метода");
        }
        removeSpan(st, leadingStart(st, first), st.end(last));
        if (members) {
            insertLineAfter(st, target.node(), text, st.newline());
        } else {
            insertStatements(st, target, text);
        }
        commit(model, change);
    }

    // ---------------------------------------------------------------- extractMethod

    /**
     * Вынести подряд идущие операторы метода в новый private-метод {@code name}. Параметры — локальные переменные
     * метода, которые фрагмент читает; если фрагмент объявляет или меняет одну переменную, нужную дальше, —
     * метод её возвращает. Отказ: return / break / continue за пределы фрагмента, несколько таких переменных,
     * переменная объявлена через var.
     */
    private void extractMethod(ProjectModel model, GraphOp op) {
        String name = op.name() == null ? "" : op.name().strip();
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))
                || !name.chars().allMatch(Character::isJavaIdentifierPart)) {
            throw new UnsupportedGraphOpException("Укажите имя нового метода (name), например: computeTotal");
        }
        List<Node> sel = selection(model, ids(op), false);
        Node first = sel.get(0);
        Node last = sel.get(sel.size() - 1);
        CallableDeclaration<?> callable = enclosingCallable(first);
        TypeDeclaration<?> type = callable.findAncestor(TypeDeclaration.class)
                .orElseThrow(() -> new UnsupportedGraphOpException("Метод вне класса"));
        if (!type.getMethodsByName(name).isEmpty()) {
            throw new UnsupportedGraphOpException("Метод " + name + " уже есть в " + type.getNameAsString());
        }
        requireLocalControlFlow(sel);

        Map<String, String> outer = visibleLocals(callable, first);
        Set<String> used = new LinkedHashSet<>();
        Set<String> assigned = new HashSet<>();
        for (Node n : sel) {
            for (NameExpr ne : n.findAll(NameExpr.class)) {
                if (outer.containsKey(ne.getNameAsString())) {
                    used.add(ne.getNameAsString());
                }
            }
            n.findAll(AssignExpr.class).forEach(a -> {
                if (a.getTarget() instanceof NameExpr t) {
                    assigned.add(t.getNameAsString());
                }
            });
            n.findAll(UnaryExpr.class).forEach(u -> {
                if (u.getExpression() instanceof NameExpr t && (u.getOperator().isPrefix() || u.getOperator().isPostfix())
                        && u.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
                        && u.getOperator() != UnaryExpr.Operator.BITWISE_COMPLEMENT
                        && u.getOperator() != UnaryExpr.Operator.PLUS && u.getOperator() != UnaryExpr.Operator.MINUS) {
                    assigned.add(t.getNameAsString());
                }
            });
        }
        Map<String, String> declared = new LinkedHashMap<>();
        for (Node n : sel) {
            if (n instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr v) {
                v.getVariables().forEach(d -> declared.put(d.getNameAsString(), typeOf(d)));
            }
        }
        Set<String> after = namesAfter(callable, last);
        List<String> outs = new ArrayList<>();
        declared.keySet().stream().filter(after::contains).forEach(outs::add);
        used.stream().filter(assigned::contains).filter(after::contains).forEach(outs::add);
        if (outs.size() > 1) {
            throw new UnsupportedGraphOpException("Фрагмент меняет несколько переменных, нужных дальше ("
                    + String.join(", ", outs) + ") — выделите меньше операторов");
        }
        String out = outs.isEmpty() ? null : outs.get(0);
        String outType = out == null ? "void" : declared.getOrDefault(out, outer.get(out));
        for (String u : used) {
            if ("var".equals(outer.get(u)) || (out != null && "var".equals(outType))) {
                throw new UnsupportedGraphOpException("Переменная объявлена через var — укажите тип явно");
            }
        }

        Change change = change(model, first);
        SourceText st = change.text();
        String memberIndent = st.indentAt(st.begin(callable));
        String unit = SourceText.deeper(memberIndent).substring(memberIndent.length());
        String nl = st.newline();
        String params = used.stream().map(u -> outer.get(u) + " " + u).collect(Collectors.joining(", "));
        String args = String.join(", ", used);
        StringBuilder method = new StringBuilder("private ");
        if (callable.isStatic()) {
            method.append("static ");
        }
        if (!callable.getTypeParameters().isEmpty()) {
            method.append(callable.getTypeParameters().stream().map(Node::toString)
                    .collect(Collectors.joining(", ", "<", "> ")));
        }
        method.append(outType).append(' ').append(name).append('(').append(params).append(')');
        if (!callable.getThrownExceptions().isEmpty()) {
            method.append(callable.getThrownExceptions().stream().map(Node::toString)
                    .collect(Collectors.joining(", ", " throws ", "")));
        }
        method.append(" {").append(nl).append(unit).append(st.reindent(blockText(st, first, last), unit));
        if (out != null) {
            method.append(nl).append(unit).append("return ").append(out).append(';');
        }
        method.append(nl).append('}');

        String call = name + "(" + args + ");";
        if (out != null) {
            call = (declared.containsKey(out) ? outType + " " : "") + out + " = " + call;
        }
        st.replace(leadingStart(st, first), st.end(last), call);
        insertLineAfter(st, callable, method.toString(), nl);
        commit(model, change);
    }

    private static CallableDeclaration<?> enclosingCallable(Node node) {
        Node n = node.getParentNode().orElse(null);
        while (n != null) {
            if (n instanceof LambdaExpr || n instanceof ObjectCreationExpr || n instanceof LocalClassDeclarationStmt) {
                throw new UnsupportedGraphOpException("Выносить код из лямбды или анонимного класса пока нельзя");
            }
            if (n instanceof CallableDeclaration<?> c) {
                return c;
            }
            if (n instanceof InitializerDeclaration || n instanceof CompactConstructorDeclaration) {
                throw new UnsupportedGraphOpException("Выносить код из инициализатора или компактного конструктора пока нельзя");
            }
            n = n.getParentNode().orElse(null);
        }
        throw new UnsupportedGraphOpException("Фрагмент вне метода");
    }

    /** return и прыжки за пределы фрагмента изменили бы поведение после выноса — отказываемся. */
    private static void requireLocalControlFlow(List<Node> sel) {
        for (Node n : sel) {
            for (Node s : n.findAll(Statement.class)) {
                if (insideNestedBody(s, n)) {
                    continue;
                }
                if (s instanceof ReturnStmt || s instanceof YieldStmt) {
                    throw new UnsupportedGraphOpException("Во фрагменте есть return — его нельзя вынести как есть");
                }
                if (s instanceof BreakStmt b && !jumpTargetInside(b, b.getLabel().map(l -> l.asString()).orElse(null), false, n)
                        || s instanceof ContinueStmt c && !jumpTargetInside(c, c.getLabel().map(l -> l.asString()).orElse(null), true, n)) {
                    throw new UnsupportedGraphOpException("Во фрагменте есть break/continue за его пределы");
                }
            }
        }
    }

    /** Оператор внутри лямбды или локального/анонимного класса — его return к фрагменту не относится. */
    private static boolean insideNestedBody(Node s, Node root) {
        Node n = s.getParentNode().orElse(null);
        while (n != null && n != root) {
            if (n instanceof LambdaExpr || n instanceof ObjectCreationExpr || n instanceof LocalClassDeclarationStmt) {
                return true;
            }
            n = n.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean jumpTargetInside(Node jump, String label, boolean loopOnly, Node root) {
        Node n = jump.getParentNode().orElse(null);
        while (n != null) {
            boolean target = label != null ? n instanceof LabeledStmt l && l.getLabel().asString().equals(label)
                    : n instanceof ForStmt || n instanceof ForEachStmt || n instanceof WhileStmt || n instanceof DoStmt
                    || (!loopOnly && n instanceof SwitchStmt);
            if (target) {
                return true;
            }
            if (n == root) {
                return false;
            }
            n = n.getParentNode().orElse(null);
        }
        return false;
    }

    /** Локальные переменные и параметры метода, видимые в начале фрагмента: имя → тип. */
    private static Map<String, String> visibleLocals(CallableDeclaration<?> callable, Node first) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (Parameter p : callable.getParameters()) {
            vars.put(p.getNameAsString(), typeText(p.getType()) + (p.isVarArgs() ? "[]" : ""));
        }
        Position start = first.getBegin().orElseThrow();
        for (VariableDeclarator d : callable.findAll(VariableDeclarator.class)) {
            if (d.getParentNode().orElse(null) instanceof FieldDeclaration || !d.getEnd().orElseThrow().isBefore(start)) {
                continue;
            }
            Node scope = scopeOf(d);
            if (scope != null && scope.isAncestorOf(first)) {
                vars.put(d.getNameAsString(), typeOf(d));
            }
        }
        for (Parameter p : callable.findAll(Parameter.class)) {
            Node owner = p.getParentNode().orElse(null);
            if ((owner instanceof CatchClause || owner instanceof LambdaExpr) && owner.isAncestorOf(first)) {
                vars.put(p.getNameAsString(), typeText(p.getType()));
            }
        }
        return vars;
    }

    /** Где видна локальная переменная: блок с её объявлением, заголовок for / for-each / try-ресурсы. */
    private static Node scopeOf(VariableDeclarator d) {
        Node n = d.getParentNode().orElse(null); // VariableDeclarationExpr
        Node p = n == null ? null : n.getParentNode().orElse(null);
        if (p instanceof ExpressionStmt es) {
            return es.getParentNode().orElse(null);
        }
        return p instanceof ForStmt || p instanceof ForEachStmt || p instanceof TryStmt ? p : null;
    }

    private static String typeOf(VariableDeclarator d) {
        return typeText(d.getType());
    }

    /** Тип как в исходнике ({@code Map<String, Integer>}, а не {@code Map<String,Integer>} из asString). */
    private static String typeText(Type type) {
        try {
            return LexicalPreservingPrinter.print(type).strip();
        } catch (RuntimeException e) {
            return type.asString();
        }
    }

    /** Имена, которые метод читает или пишет после фрагмента. */
    private static Set<String> namesAfter(CallableDeclaration<?> callable, Node last) {
        Position end = last.getEnd().orElseThrow();
        Set<String> names = new HashSet<>();
        for (NameExpr ne : callable.findAll(NameExpr.class)) {
            if (ne.getBegin().orElseThrow().isAfter(end)) {
                names.add(ne.getNameAsString());
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- выделение

    private static List<String> ids(GraphOp op) {
        if (op.nodeIds() != null && !op.nodeIds().isEmpty()) {
            return op.nodeIds();
        }
        if (op.nodeId() != null) {
            return List.of(op.nodeId());
        }
        throw new UnsupportedGraphOpException("Не выбраны узлы (nodeIds)");
    }

    /**
     * Выделение — подряд идущие операторы одного блока (или члены одного класса, если {@code allowMembers}),
     * в порядке кода.
     */
    private static List<Node> selection(ProjectModel model, List<String> ids, boolean allowMembers) {
        List<Node> nodes = new ArrayList<>();
        for (String id : ids) {
            Target t = resolve(model, id);
            if (t.anchor() != Anchor.NODE) {
                throw new UnsupportedGraphOpException("Начало и конец блок-схемы выделить нельзя");
            }
            Node n = t.node() instanceof Statement s && !isFinally(s) ? outerLabeled(s) : t.node();
            if (!(n instanceof Statement) || isFinally(n)) {
                if (!(allowMembers && n instanceof BodyDeclaration<?>)) {
                    throw new UnsupportedGraphOpException("Выделять можно только операторы"
                            + (allowMembers ? " или члены класса" : ""));
                }
            }
            if (nodes.stream().noneMatch(x -> x == n)) {
                nodes.add(n);
            }
        }
        nodes.sort((a, b) -> a.getBegin().orElseThrow().compareTo(b.getBegin().orElseThrow()));
        NodeList<? extends Node> list = siblings(nodes.get(0));
        if (nodes.size() == 1) {
            return nodes;
        }
        if (list == null) {
            throw new UnsupportedGraphOpException("Выделенные блоки должны стоять подряд в одном блоке");
        }
        int at = indexOf(list, nodes.get(0));
        for (int i = 1; i < nodes.size(); i++) {
            if (siblings(nodes.get(i)) != list || indexOf(list, nodes.get(i)) != at + i) {
                throw new UnsupportedGraphOpException("Выделенные блоки должны стоять подряд в одном блоке");
            }
        }
        return nodes;
    }

    private static NodeList<? extends Node> siblings(Node n) {
        if (n instanceof Statement s) {
            return statementList(s);
        }
        Node parent = n.getParentNode().orElse(null);
        if (parent instanceof TypeDeclaration<?> t) {
            return t.getMembers();
        }
        return parent instanceof CompilationUnit cu ? cu.getTypes() : null;
    }

    /** Текст выделения целыми строками (с комментарием над первым узлом) — для переноса с новым отступом. */
    private static String blockText(SourceText st, Node first, Node last) {
        int start = leadingStart(st, first);
        int from = st.blankBefore(start) ? st.lineStart(start) : start;
        return st.slice(from, st.end(last));
    }

    // ---------------------------------------------------------------- поиск цели

    private enum Anchor { NODE, START, END }

    private record Target(Node node, Anchor anchor) {
    }

    private static Target resolve(ProjectModel model, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new UnsupportedGraphOpException("Не указан узел (nodeId)");
        }
        Anchor anchor = Anchor.NODE;
        String id = nodeId;
        if (id.endsWith(":start")) {
            anchor = Anchor.START;
            id = id.substring(0, id.length() - ":start".length());
        } else if (id.endsWith(":end")) {
            anchor = Anchor.END;
            id = id.substring(0, id.length() - ":end".length());
        }
        Node node = id.startsWith("g") ? model.nodeById(id.substring(1)).orElse(null) : null;
        if (node == null) {
            throw new UnsupportedGraphOpException("Узел " + nodeId + " не найден — обновите граф");
        }
        if (anchor != Anchor.NODE) {
            if (!(node instanceof CallableDeclaration<?> || node instanceof InitializerDeclaration
                    || node instanceof CompactConstructorDeclaration)) {
                throw new UnsupportedGraphOpException("Узел " + nodeId + " не начало метода");
            }
            return new Target(node, anchor);
        }
        return new Target(owner(node), Anchor.NODE);
    }

    /** Выражение → оператор или объявление, которому оно принадлежит; блоки и члены — как есть. */
    private static Node owner(Node node) {
        Node n = node;
        while (n != null) {
            if (n instanceof Statement || n instanceof SwitchEntry || n instanceof CatchClause
                    || n instanceof BodyDeclaration<?>) {
                return n;
            }
            n = n.getParentNode().orElse(null);
        }
        throw new UnsupportedGraphOpException("Узел не принадлежит методу или классу");
    }

    private static boolean isFinally(Node node) {
        return node instanceof BlockStmt b && b.getParentNode().orElse(null) instanceof TryStmt t
                && t.getFinallyBlock().filter(f -> f == b).isPresent();
    }

    /** {@code case X -> expr;} / {@code case X -> throw ...;} — тело из одного оператора без фигурных скобок. */
    private static boolean isArrowSingle(SwitchEntry entry) {
        return entry.getType() == SwitchEntry.Type.EXPRESSION || entry.getType() == SwitchEntry.Type.THROWS_STATEMENT;
    }

    /** Конец заголовка case: последней метки или слова default. */
    private static int headerEnd(SourceText st, SwitchEntry entry) {
        if (entry.getLabels().isEmpty()) {
            int begin = st.begin(entry);
            if (!st.slice(begin, Math.min(st.text().length(), begin + 7)).equals("default")) {
                throw new UnsupportedGraphOpException("Не удалось найти заголовок case");
            }
            return begin + "default".length();
        }
        return st.end(entry.getLabels().getLast().orElseThrow());
    }

    /** {@code outer: for (...)} — вставка и удаление цикла с меткой касаются всего помеченного оператора. */
    private static Statement outerLabeled(Statement s) {
        Statement st = s;
        while (st.getParentNode().orElse(null) instanceof LabeledStmt l) {
            st = l;
        }
        return st;
    }

    /** Список операторов, в котором стоит {@code s}, или null, если это одиночное тело (if (x) a();). */
    private static NodeList<Statement> statementList(Statement s) {
        Node parent = s.getParentNode().orElse(null);
        if (parent instanceof BlockStmt b) {
            return b.getStatements();
        }
        if (parent instanceof SwitchEntry e && e.getType() == SwitchEntry.Type.STATEMENT_GROUP) {
            return e.getStatements();
        }
        return null;
    }

    /** Индекс по ссылке: {@code NodeList.indexOf} сравнивает структурно и спутает два одинаковых узла. */
    private static int indexOf(NodeList<? extends Node> list, Node node) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == node) {
                return i;
            }
        }
        throw new UnsupportedGraphOpException("Узел не найден в родителе — обновите граф");
    }

    private static String requireCode(GraphOp op) {
        if (op.code() == null || op.code().isBlank()) {
            throw new UnsupportedGraphOpException("Не указан код (code)");
        }
        return op.code();
    }
}
