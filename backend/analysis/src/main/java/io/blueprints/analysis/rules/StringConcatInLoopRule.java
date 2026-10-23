package io.blueprints.analysis.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
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
import java.util.List;

/**
 * Конкатенация строк в цикле: {@code s += item} или {@code s = s + item}.
 *
 * <p>Строка неизменяема: каждая конкатенация копирует накопленное значение, поэтому n сложений
 * в цикле дают O(n²) по суммарной длине. {@code StringBuilder} делает то же за O(n).
 */
public final class StringConcatInLoopRule implements Rule {

    private static final String RULE = "string-concat-in-loop";

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (AssignExpr assign : cu.findAll(AssignExpr.class)) {
                int depth = Ast.loopDepth(assign);
                if (depth == 0) {
                    continue;
                }
                String variable = targetName(assign.getTarget());
                if (variable == null || !isString(assign, variable)) {
                    continue;
                }
                boolean concatenates = switch (assign.getOperator()) {
                    case PLUS -> true;
                    case ASSIGN -> appendsSelf(assign, variable);
                    default -> false;
                };
                if (concatenates) {
                    findings.add(finding(model, assign, variable, depth));
                }
            }
        });
        return findings;
    }

    /** {@code s = s + x}: слева и справа одна и та же переменная. */
    private static boolean appendsSelf(AssignExpr assign, String variable) {
        if (!(assign.getValue() instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.PLUS) {
            return false;
        }
        Expression left = binary.getLeft();
        Expression target = assign.getTarget();
        return Ast.compact(left.toString()).equals(Ast.compact(target.toString()))
                || (left instanceof NameExpr name && name.getNameAsString().equals(variable));
    }

    /** Переменная объявлена как String или инициализирована строковым литералом. */
    private static boolean isString(Node context, String variable) {
        String type = CostModel.declaredType(context, variable);
        if (type != null) {
            return "String".equalsIgnoreCase(type);
        }
        for (VariableDeclarator declarator : context.findAll(VariableDeclarator.class)) {
            if (declarator.getNameAsString().equals(variable)
                    && declarator.getInitializer().orElse(null) instanceof StringLiteralExpr) {
                return true;
            }
        }
        return false;
    }

    private static String targetName(Expression target) {
        if (target instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (target instanceof FieldAccessExpr field) {
            return field.getNameAsString();
        }
        return null;
    }

    private static Finding finding(ProjectModel model, AssignExpr assign, String variable, int depth) {
        BigO current = BigO.of(depth + 1);
        BigO after = BigO.of(depth);
        String preview = "StringBuilder " + variable + "Builder = new StringBuilder();\n"
                + "// внутри цикла\n"
                + variable + "Builder.append(expr);\n"
                + "String " + variable + " = " + variable + "Builder.toString();";
        return Findings.of(model, RULE, Severity.HIGH,
                "Конкатенация строк в цикле — O(n²)",
                "Переменная " + variable + " типа String накапливается через +=: каждая итерация копирует "
                        + "уже собранную строку, суммарно " + current + ". StringBuilder добавляет за O(1) "
                        + "и даёт " + after + ".",
                assign, List.of(assign),
                new Complexity(current.toString(), after.toString()),
                new Fix("string-builder", "Заменить конкатенацию на StringBuilder", preview, false),
                "Убирает копирование строки на каждой итерации: " + current + " → " + after);
    }
}
