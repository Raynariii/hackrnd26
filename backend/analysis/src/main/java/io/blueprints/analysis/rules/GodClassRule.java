package io.blueprints.analysis.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.blueprints.analysis.Finding;
import io.blueprints.analysis.Fix;
import io.blueprints.analysis.Findings;
import io.blueprints.analysis.Rule;
import io.blueprints.analysis.Severity;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

/**
 * God-class: класс, который делает всё сам.
 *
 * <p>Признаки — много методов, много полей или большой размер. Такой класс нельзя нормально
 * проверить тестами: у него нет одной ответственности. Для монолита из кейса это главный
 * кандидат на декомпозицию ({@code POST /decompose}).
 */
public final class GodClassRule implements Rule {

    private static final String RULE = "god-class";

    private final int maxMethods;
    private final int maxFields;
    private final int maxLines;

    public GodClassRule(int maxMethods, int maxFields, int maxLines) {
        this.maxMethods = maxMethods;
        this.maxFields = maxFields;
        this.maxLines = maxLines;
    }

    @Override
    public String id() {
        return RULE;
    }

    @Override
    public List<Finding> check(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface()) {
                    continue;
                }
                int methods = type.getMethods().size();
                int fields = type.getFields().size();
                int lines = type.getRange().map(range -> range.end.line - range.begin.line + 1).orElse(0);
                if (methods <= maxMethods && fields <= maxFields && lines <= maxLines) {
                    continue;
                }
                findings.add(finding(model, type, methods, fields, lines));
            }
        });
        return findings;
    }

    private Finding finding(ProjectModel model, ClassOrInterfaceDeclaration type, int methods, int fields, int lines) {
        List<String> reasons = new ArrayList<>();
        if (methods > maxMethods) {
            reasons.add(methods + " методов при пороге " + maxMethods);
        }
        if (fields > maxFields) {
            reasons.add(fields + " полей при пороге " + maxFields);
        }
        if (lines > maxLines) {
            reasons.add(lines + " строк при пороге " + maxLines);
        }
        boolean severe = methods > maxMethods * 2 || lines > maxLines * 2;
        return Findings.of(model, RULE, severe ? Severity.HIGH : Severity.MEDIUM,
                "God-class: у класса слишком много ответственностей",
                "Класс " + type.getNameAsString() + " — " + String.join(", ", reasons)
                        + ". Его части меняются независимо, но правки в одной ломают другую: "
                        + "разделите ответственности по классам (декомпозиция монолита).",
                type, List.of(type),
                null,
                new Fix("decompose", "Разложить класс на Parser/Analyzer/Optimizer/Main", null, false),
                "Каждый класс получает одну ответственность и проверяется отдельно");
    }
}
