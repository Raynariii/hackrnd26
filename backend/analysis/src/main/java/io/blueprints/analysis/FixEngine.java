package io.blueprints.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.VarType;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Детерминированные авто-исправления: правят AST, а не текст, поэтому результат — валидный Java,
 * который дальше проходит quality gate (компиляция + дифф-тесты, роль 4).
 *
 * <p>Работает так же, как операции над графом в ядре: парсим <em>копию</em> текущих исходников,
 * меняем AST, печатаем через {@link CodeGenerator} (форматирование сохраняется). Сессия
 * пользователя не портится: при ошибке ничего не применяется.
 *
 * <p>Поддержаны четыре исправления из топ-4 правил (см. {@code Analyzer.defaultRules()}):
 * индекс {@code HashSet} вместо поиска по списку, {@code StringBuilder} вместо конкатенации,
 * обход {@code LinkedList} через for-each, константа вместо «магического» числа. Каждое
 * исправление отказывается работать, если не может гарантировать сохранение поведения
 * (переменная объявлена внутри цикла, коллекция меняется в цикле, строку читают в цикле, …).
 */
public final class FixEngine {

    /** Применённое исправление: что сделали и как теперь выглядят исходники. */
    public record Applied(Finding finding, List<SourceFile> files, String description) {
    }

    private static final Set<Long> ALLOWED_MAGIC = Set.of(-1L, 0L, 1L, 2L);

    private final JavaProjectParser parser = new JavaProjectParser();
    private final CodeGenerator codeGenerator = new CodeGenerator();

    /**
     * Применяет исправление к копии исходников.
     *
     * @param files     текущие файлы проекта
     * @param findingId id находки из {@code GET /diagnostics}
     * @throws FixNotApplicableException если находка не найдена или исправление небезопасно
     */
    public Applied apply(List<SourceFile> files, String findingId) {
        ProjectModel model = parser.parse(files);
        Finding finding = find(model, findingId);
        if (finding.fix() == null) {
            throw new FixNotApplicableException("У находки " + findingId + " нет предложенного исправления");
        }
        Node anchor = anchorNode(model, finding);
        // Тот же расчёт, что выставляет Fix.autoApplicable в диагностике: текст отказа совпадает с UI.
        FixSafety.require(finding.rule(), anchor);
        String description = switch (finding.rule()) {
            case "collection-lookup-in-loop" -> indexInsteadOfLookup((MethodCallExpr) anchor);
            case "string-concat-in-loop" -> stringBuilder((AssignExpr) anchor);
            case "magic-number" -> constantInsteadOfMagicNumber((Statement) anchor);
            case "linked-list-index-access" -> forEachInsteadOfIndex((MethodCallExpr) anchor);
            default -> throw new FixNotApplicableException("Для правила " + finding.rule()
                    + " авто-исправление не реализовано");
        };
        return new Applied(finding, codeGenerator.print(model), description);
    }

    private static Finding find(ProjectModel model, String findingId) {
        return new Analyzer(Analyzer.defaultRules()).analyze(model).findings().stream()
                .filter(finding -> finding.id().equals(findingId))
                .findFirst()
                .orElseThrow(() -> new FixNotApplicableException(
                        "Находка " + findingId + " не актуальна: код изменился, перечитайте диагностику"));
    }

    private static Node anchorNode(ProjectModel model, Finding finding) {
        if (finding.nodeIds().isEmpty()) {
            throw new FixNotApplicableException("У находки нет привязки к узлу кода");
        }
        String nodeId = finding.nodeIds().get(0);
        return model.nodeById(nodeId.substring(1))
                .orElseThrow(() -> new FixNotApplicableException("Узел " + nodeId + " не найден в текущей версии"));
    }

    // ---------------------------------------------------------------- HashSet вместо поиска

    private String indexInsteadOfLookup(MethodCallExpr call) {
        FixSafety.require("collection-lookup-in-loop", call);
        Node loop = nearestLoop(call);
        String receiver = receiverOrFail(call);
        String index = uniqueName(call, "_index");
        Statement declaration = StaticJavaParser.parseStatement(
                "var " + index + " = new java.util.HashSet<>(" + receiver + ");");
        insertBefore(loopStatement(loop), declaration);
        for (MethodCallExpr candidate : loop.findAll(MethodCallExpr.class)) {
            if (candidate.getNameAsString().equals("contains") && receiver.equals(FixSafety.receiverName(candidate))) {
                candidate.setScope(new NameExpr(index));
            }
        }
        return "Построен HashSet " + index + ": contains больше не перебирает список";
    }

    // ---------------------------------------------------------------- StringBuilder вместо +=

    private String stringBuilder(AssignExpr assign) {
        FixSafety.require("string-concat-in-loop", assign);
        Node loop = nearestLoop(assign);
        String variable = targetOrFail(assign);
        String builder = uniqueName(assign, "_sb");
        insertBefore(loopStatement(loop), StaticJavaParser.parseStatement(
                "var " + builder + " = new java.lang.StringBuilder();"));
        for (AssignExpr candidate : loop.findAll(AssignExpr.class)) {
            if (!variable.equals(FixSafety.assignTargetName(candidate))) {
                continue;
            }
            Expression appended = FixSafety.appendedValue(candidate);
            if (appended == null) {
                continue;
            }
            candidate.replace(new MethodCallExpr(new NameExpr(builder), "append", new NodeList<>(appended.clone())));
        }
        insertAfter(loopStatement(loop), StaticJavaParser.parseStatement(
                variable + " = " + builder + ".toString();"));
        return "Конкатенация заменена на StringBuilder " + builder + ": строка не копируется на каждой итерации";
    }

    // ---------------------------------------------------------------- константа вместо числа

    private String constantInsteadOfMagicNumber(Statement statement) {
        FixSafety.require("magic-number", statement);
        ClassOrInterfaceDeclaration type = statement.findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new FixNotApplicableException("Число вне класса: константу некуда вынести"));
        Map<String, String> constants = new LinkedHashMap<>();
        List<Expression> literals = new ArrayList<>();
        for (IntegerLiteralExpr literal : statement.findAll(IntegerLiteralExpr.class)) {
            if (!ALLOWED_MAGIC.contains(asLong(literal))) {
                literals.add(literal);
            }
        }
        literals.addAll(statement.findAll(DoubleLiteralExpr.class));
        Set<String> declared = new HashSet<>();
        for (Expression literal : literals) {
            String value = literal.toString();
            String name = constants.computeIfAbsent(value,
                    ignored -> uniqueName(statement, "LIMIT_" + safeName(value)));
            if (declared.add(name)) {
                String javaType = literal instanceof DoubleLiteralExpr ? "double" : "int";
                type.getMembers().add(0, StaticJavaParser.parseBodyDeclaration(
                        "private static final " + javaType + " " + name + " = " + value + ";"));
            }
            literal.replace(new NameExpr(name));
        }
        return "Числа заменены константами " + String.join(", ", new LinkedHashSet<>(constants.values()));
    }

    private static String safeName(String value) {
        return value.replace('-', 'M').replace('.', '_').replaceAll("[^A-Za-z0-9_]", "");
    }

    // ---------------------------------------------------------------- for-each вместо get(i)

    private String forEachInsteadOfIndex(MethodCallExpr call) {
        FixSafety.require("linked-list-index-access", call);
        Node loopNode = nearestLoop(call);
        if (!(loopNode instanceof ForStmt loop)) {
            throw new FixNotApplicableException("Обращение по индексу вне классического for: меняйте вручную");
        }
        String counter = Ast.counterVariable(loop);
        String receiver = receiverOrFail(call);
        if (counter == null) {
            throw new FixNotApplicableException("У цикла нет переменной-счётчика");
        }
        String item = uniqueName(call, "_item");
        for (MethodCallExpr candidate : loop.getBody().findAll(MethodCallExpr.class)) {
            if ("get".equals(candidate.getNameAsString()) && receiver.equals(FixSafety.receiverName(candidate))) {
                candidate.replace(new NameExpr(item));
            }
        }
        ForEachStmt each = new ForEachStmt(
                new VariableDeclarationExpr(new VarType(), item),
                new NameExpr(receiver),
                loop.getBody());
        loop.replace(each);
        return "Индексация заменена на for-each: LinkedList обходится за один проход";
    }

    // ---------------------------------------------------------------- общие проверки и правки

    private static Node nearestLoop(Node node) {
        return Ast.enclosingLoops(node).stream().findFirst()
                .orElseThrow(() -> new FixNotApplicableException("Узел не внутри цикла"));
    }

    private static Statement loopStatement(Node loop) {
        if (loop instanceof Statement statement) {
            return statement;
        }
        throw new FixNotApplicableException("Цикл не является оператором — исправление вручную");
    }

    private static String receiverOrFail(MethodCallExpr call) {
        String receiver = FixSafety.receiverName(call);
        if (receiver == null) {
            throw new FixNotApplicableException("Получатель вызова — не простая переменная: исправление вручную");
        }
        return receiver;
    }

    private static String targetOrFail(AssignExpr assign) {
        String target = FixSafety.assignTargetName(assign);
        if (target == null) {
            throw new FixNotApplicableException("Слева не переменная: исправление вручную");
        }
        return target;
    }

    private static void insertBefore(Statement target, Statement statement) {
        Node parent = target.getParentNode().orElseThrow(() -> new FixNotApplicableException("Не найден блок для вставки"));
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(target);
            block.getStatements().add(index, statement);
            return;
        }
        BlockStmt wrapper = new BlockStmt();
        target.replace(wrapper);
        wrapper.addStatement(statement);
        wrapper.addStatement(target);
    }

    private static void insertAfter(Statement target, Statement statement) {
        Node parent = target.getParentNode().orElseThrow(() -> new FixNotApplicableException("Не найден блок для вставки"));
        if (parent instanceof BlockStmt block) {
            int index = block.getStatements().indexOf(target);
            block.getStatements().add(index + 1, statement);
            return;
        }
        BlockStmt wrapper = new BlockStmt();
        target.replace(wrapper);
        wrapper.addStatement(target);
        wrapper.addStatement(statement);
    }

    private static String uniqueName(Node context, String base) {
        Set<String> used = new HashSet<>();
        MethodDeclaration method = context.findAncestor(MethodDeclaration.class).orElse(null);
        if (method != null) {
            method.getParameters().forEach(parameter -> used.add(parameter.getNameAsString()));
            method.findAll(VariableDeclarator.class).forEach(declarator -> used.add(declarator.getNameAsString()));
        }
        context.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(type -> type.getFields()
                .forEach(field -> field.getVariables().forEach(declarator -> used.add(declarator.getNameAsString()))));
        if (!used.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static long asLong(IntegerLiteralExpr literal) {
        try {
            return literal.asNumber().longValue();
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
