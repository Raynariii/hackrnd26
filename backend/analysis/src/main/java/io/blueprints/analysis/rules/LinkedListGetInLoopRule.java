package io.blueprints.analysis.rules;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.BigO;
import io.blueprints.analysis.Complexity;
import io.blueprints.analysis.CostModel;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Обращение к {@code LinkedList} по индексу в цикле: {@code list.get(i)}.
 *
 * <p>{@code LinkedList} не хранит массив: {@code get(i)} идёт от начала списка, то есть O(n), а в цикле —
 * O(n²). Обход через for-each или {@code Iterator} — O(n).
 */
public final class LinkedListGetInLoopRule implements Rule {

    private static final String RULE = "linked-list-index-access";

    private final CostModel costModel = new CostModel();

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                if (!"get".equals(call.getNameAsString())) {
                    continue;
                }
                int depth = Ast.loopDepth(call);
                if (depth == 0) {
                    continue;
                }
                if (!costModel.isLinearIndexAccess(call) && !linkedListField(call)) {
                    continue;
                }
                findings.add(finding(model, call, depth));
            }
        });
        return findings;
    }

    /**
     * Получатель — поле объекта ({@code book.waitList.get(i)}): без SymbolSolver тип поля не вывести,
     * поэтому ищем объявление поля по имени во всём файле. Ровно одно объявление и это {@code LinkedList} —
     * обращение линейное; имя встречается у нескольких классов — не гадаем.
     */
    private static boolean linkedListField(MethodCallExpr call) {
        if (!(call.getScope().orElse(null) instanceof FieldAccessExpr field)) {
            return false;
        }
        String name = field.getNameAsString();
        var unit = call.findCompilationUnit().orElse(null);
        if (unit == null) {
            return false;
        }
        Set<String> types = new LinkedHashSet<>();
        for (FieldDeclaration declaration : unit.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator declarator : declaration.getVariables()) {
                if (declarator.getNameAsString().equals(name)) {
                    types.add(simpleName(declaration.getElementType().asString()));
                }
            }
        }
        return types.size() == 1 && "LinkedList".equalsIgnoreCase(types.iterator().next());
    }

    private static String simpleName(String type) {
        int generic = type.indexOf('<');
        String name = generic >= 0 ? type.substring(0, generic) : type;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private static Finding finding(ProjectModel model, MethodCallExpr call, int depth) {
        BigO current = BigO.of(depth + 1);
        BigO after = BigO.of(depth);
        String receiver = call.getScope().map(Object::toString).orElse("list");
        String preview = "for (T item : " + receiver + ") {\n    // вместо " + receiver + ".get(i)\n}";
        return Findings.of(model, RULE, Severity.HIGH,
                "LinkedList.get(i) в цикле — O(n²)",
                "У LinkedList доступ по индексу идёт от головы списка и стоит O(n). В цикле это " + current
                        + "; обход через for-each/Iterator — " + after + ".",
                call, List.of(call),
                new Complexity(current.toString(), after.toString()),
                new Fix("iterator", "Заменить обращение по индексу на обход Iterator/for-each", preview, true),
                "Убирает проход по списку на каждой итерации: " + current + " → " + after);
    }
}
