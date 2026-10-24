package io.blueprints.analysis.fixes;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.ops.DefaultGraphOperations;
import io.blueprints.core.ops.GraphOp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Точный дубль → вынести в общий private-метод средствами ядра ({@code GraphOperations.extractMethod}).
 *
 * <p>Правка безопасна, только если повторяющиеся фрагменты <b>текстуально идентичны</b>: тогда один и
 * тот же код с теми же именами переменных можно вызвать из обоих мест. Из первого вхождения ядро делает
 * метод и подставляет вызов; во второе мы вставляем клон этого же вызова — типы и аргументы уже
 * посчитаны ядром, второй раз их выводить не нужно.
 *
 * <p>Отказы: вхождения в разных классах, текст различается, ядро отказало (return/break наружу,
 * несколько выходных переменных, {@code var}), либо вызов оказался объявлением переменной — тогда
 * клон во втором месте конфликтовал бы по имени. Причина отказа та же, что увидит пользователь, потому
 * что проверка выполняется на копии модели.
 */
public final class ExtractDuplicateFix implements Fixer {

    private static final int MIN_RUN = 3;
    private static final int MAX_RUN = 12;

    @Override
    public String kind() {
        return "extract-duplicate";
    }

    @Override
    public String refusalReason(ProjectModel model, Finding finding) {
        try {
            Plan plan = plan(model, finding);
            // Проверяем на копии модели: ядро должно отказать там же, где откажет при применении.
            // Id узлов после печати и повторного разбора другие — вхождения ищем по методу и индексу.
            ProjectModel copy = copyOf(model);
            Statement replacement = extract(copy, relocate(copy, plan));
            if (!(replacement instanceof ExpressionStmt)) {
                return "Выделенный метод возвращает значение — автоматическое исправление второго "
                        + "вхождения изменило бы область видимости";
            }
            return null;
        } catch (FixNotApplicableException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return FixAnchors.manual(finding);
        }
    }

    /** Переносит план на копию модели: методы ищем по имени, оператор — по индексу в теле. */
    private static Plan relocate(ProjectModel copy, Plan plan) {
        List<Occurrence> occurrences = new ArrayList<>();
        for (Occurrence occurrence : plan.occurrences()) {
            String name = occurrence.method().getNameAsString();
            MethodDeclaration method = copy.units().values().stream()
                    .flatMap(unit -> unit.findAll(MethodDeclaration.class).stream())
                    .filter(candidate -> candidate.getNameAsString().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new FixNotApplicableException("Метод " + name + " не найден в копии"));
            BlockStmt block = method.getBody()
                    .orElseThrow(() -> new FixNotApplicableException("У метода " + name + " нет тела"));
            occurrences.add(new Occurrence(block, occurrence.index(), occurrence.first(), method));
        }
        return new Plan(List.copyOf(occurrences), plan.run());
    }

    @Override
    public boolean supports(ProjectModel model, Finding finding) {
        return refusalReason(model, finding) == null;
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        Plan plan = plan(model, finding);
        Statement replacement = extract(model, plan);
        if (!(replacement instanceof ExpressionStmt call)) {
            throw new FixNotApplicableException("Выделенный метод возвращает значение — правка второго "
                    + "вхождения изменила бы область видимости");
        }
        Occurrence second = plan.occurrences().get(1);
        int count = plan.run();
        // Ядро пересобрало файл — второе вхождение берём из новой модели по имени метода и индексу.
        Statement target = resolve(model, second.method().getNameAsString(), second.index());
        BlockStmt block = (BlockStmt) target.getParentNode()
                .orElseThrow(() -> new FixNotApplicableException("Второе вхождение потерялось: исправление вручную"));
        // Заменяем первый оператор вхождения вызовом и удаляем остальные: так LexicalPreservingPrinter
        // видит замену одного узла, а не «удалить пять и вставить новый».
        block.setStatement(block.getStatements().indexOf(target), call.clone());
        for (int index = 0; index < count - 1; index++) {
            block.getStatements().remove(second.index() + 1);
        }
        return "Дубль вынесен в общий метод: " + count + " операторов вместо двух копий";
    }

    // ---------------------------------------------------------------- план и проверки

    private record Occurrence(BlockStmt block, int index, Statement first, MethodDeclaration method) {
    }

    private record Plan(List<Occurrence> occurrences, int run) {
    }

    private static Plan plan(ProjectModel model, Finding finding) {
        List<Occurrence> occurrences = new ArrayList<>();
        for (String nodeId : finding.nodeIds()) {
            Node node = model.nodeById(nodeId.startsWith("g") ? nodeId.substring(1) : nodeId)
                    .orElseThrow(() -> new FixNotApplicableException("Узел находки не найден в текущей версии"));
            if (!(node instanceof Statement statement)) {
                throw new FixNotApplicableException("Дубль указывает не на оператор: исправление вручную");
            }
            BlockStmt block = statement.getParentNode()
                    .filter(BlockStmt.class::isInstance).map(BlockStmt.class::cast)
                    .orElseThrow(() -> new FixNotApplicableException("Оператор вне блока: исправление вручную"));
            MethodDeclaration method = statement.findAncestor(MethodDeclaration.class)
                    .orElseThrow(() -> new FixNotApplicableException("Оператор вне метода: исправление вручную"));
            occurrences.add(new Occurrence(block, block.getStatements().indexOf(statement), statement, method));
        }
        if (occurrences.size() < 2) {
            throw new FixNotApplicableException("Нужны два вхождения дубля: исправление вручную");
        }
        ClassOrInterfaceDeclaration owner = occurrences.get(0).method()
                .findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new FixNotApplicableException("Метод вне класса: исправление вручную"));
        for (Occurrence occurrence : occurrences) {
            boolean sameOwner = occurrence.method().findAncestor(ClassOrInterfaceDeclaration.class)
                    .filter(type -> type == owner).isPresent();
            if (!sameOwner) {
                throw new FixNotApplicableException("Вхождения дубля в разных классах: исправление вручную");
            }
        }
        int run = runLength(occurrences.get(0), occurrences.get(1));
        if (run < MIN_RUN) {
            throw new FixNotApplicableException("Вхождения различаются: выносить нечего");
        }
        return new Plan(List.copyOf(occurrences), run);
    }

    /** Длина совпадающего префикса двух вхождений (по тексту операторов). */
    private static int runLength(Occurrence first, Occurrence second) {
        int limit = Math.min(MAX_RUN, Math.min(
                first.block().getStatements().size() - first.index(),
                second.block().getStatements().size() - second.index()));
        int run = 0;
        while (run < limit) {
            String left = text(first.block().getStatements().get(first.index() + run));
            String right = text(second.block().getStatements().get(second.index() + run));
            if (!left.equals(right)) {
                break;
            }
            run++;
        }
        return run;
    }

    private static String text(Statement statement) {
        return statement.toString().replaceAll("\\s+", " ").strip();
    }

    /** Выполняет операцию ядра и возвращает оператор, которым заменено первое вхождение. */
    private static Statement extract(ProjectModel model, Plan plan) {
        Occurrence first = plan.occurrences().get(0);
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < plan.run(); index++) {
            ids.add("g" + model.idOf(first.block().getStatements().get(first.index() + index)));
        }
        String name = FixSupport.uniqueName(first.method(), "extracted");
        try {
            new DefaultGraphOperations().apply(model,
                    new GraphOp("extractMethod", null, ids, null, null, null, name));
        } catch (RuntimeException e) {
            throw new FixNotApplicableException("Ядро отказало в выделении метода: " + message(e));
        }
        // Ядро пересобирает файл, поэтому ищем замену заново — по методу и индексу, а не по старым узлам.
        Statement replacement = resolve(model, first.method().getNameAsString(), first.index());
        if (!(replacement instanceof ExpressionStmt statement) || !(statement.getExpression() instanceof MethodCallExpr)) {
            throw new FixNotApplicableException("Выделение метода вернуло не вызов: исправление вручную");
        }
        return replacement;
    }

    /** Свежий оператор по имени метода и индексу в его теле (после пересборки модели ядром). */
    private static Statement resolve(ProjectModel model, String methodName, int index) {
        MethodDeclaration method = model.units().values().stream()
                .flatMap(unit -> unit.findAll(MethodDeclaration.class).stream())
                .filter(candidate -> candidate.getNameAsString().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new FixNotApplicableException("Метод " + methodName + " пропал после правки"));
        BlockStmt block = method.getBody()
                .orElseThrow(() -> new FixNotApplicableException("У метода " + methodName + " нет тела"));
        if (index >= block.getStatements().size()) {
            throw new FixNotApplicableException("Оператор на месте дубля не найден: исправление вручную");
        }
        return block.getStatements().get(index);
    }

    private static String message(RuntimeException e) {
        return Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
    }

    private static ProjectModel copyOf(ProjectModel model) {
        return new JavaProjectParser().parse(new CodeGenerator().print(model));
    }
}
