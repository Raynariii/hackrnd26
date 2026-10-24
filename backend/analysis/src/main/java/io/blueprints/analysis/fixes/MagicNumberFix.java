package io.blueprints.analysis.fixes;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.Statement;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * «Магическое» число в условии → {@code private static final} константа класса.
 *
 * <p>Отказываемся, если число стоит вне класса: константу некуда выносить, автоматическая правка
 * потребовала бы менять структуру файла.
 */
public final class MagicNumberFix implements Fixer {

    private static final Set<Long> ALLOWED_MAGIC = Set.of(-1L, 0L, 1L, 2L);

    @Override
    public String kind() {
        return "extract-constant";
    }

    @Override
    public String refusalReason(ProjectModel model, Finding finding) {
        try {
            Node anchor = FixAnchors.anchor(model, finding);
            if (!(anchor instanceof Statement statement)) {
                return FixAnchors.manual(finding);
            }
            statement.findAncestor(ClassOrInterfaceDeclaration.class)
                    .orElseThrow(() -> new FixNotApplicableException("Число вне класса: константу некуда вынести"));
            return null;
        } catch (FixNotApplicableException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return FixAnchors.manual(finding);
        }
    }

    @Override
    public String apply(ProjectModel model, Finding finding) {
        Statement statement = (Statement) FixAnchors.anchor(model, finding);
        ClassOrInterfaceDeclaration type = statement.findAncestor(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new FixNotApplicableException("Число вне класса: константу некуда вынести"));
        Map<String, String> constants = new LinkedHashMap<>();
        List<Expression> literals = new ArrayList<>();
        for (IntegerLiteralExpr literal : statement.findAll(IntegerLiteralExpr.class)) {
            if (!ALLOWED_MAGIC.contains(FixSupport.asLong(literal))) {
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
}
