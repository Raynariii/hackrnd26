package io.blueprints.analysis.fixes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.analysis.FixSafety;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Число в условии → {@code private static final} константа класса. */
final class ConstantFixer implements Fixer {

    private static final Set<Long> ALLOWED_MAGIC = Set.of(-1L, 0L, 1L, 2L);

    @Override
    public String rule() {
        return "magic-number";
    }

    @Override
    public String kind() {
        return "extract-constant";
    }

    @Override
    public String explain(Finding finding, ProjectModel model) {
        return FixSafety.explain(rule(), FixSupport.anchor(model, finding));
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        Statement statement = (Statement) FixSupport.anchor(model, finding);
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
                    ignored -> FixSupport.uniqueName(statement, "LIMIT_" + safeName(value)));
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

    private static long asLong(IntegerLiteralExpr literal) {
        try {
            return literal.asNumber().longValue();
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
