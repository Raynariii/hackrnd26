package io.blueprints.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.blueprints.analysis.rules.LongMethodRule;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;

public final class Analyzer {

    private final List<Rule> rules;

    public Analyzer(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static Analyzer withDefaultRules() {
        return new Analyzer(defaultRules());
    }

    /** TODO(роль 2): добавить правила из PLAN.md §4. */
    public static List<Rule> defaultRules() {
        return List.of(new LongMethodRule(40));
    }

    public AnalysisReport analyze(ProjectModel model) {
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            findings.addAll(rule.check(model));
        }
        return new AnalysisReport(findings, metrics(model));
    }

    /** TODO(роль 2): цикломатическая сложность, вложенность, Big O. */
    private static List<MethodMetrics> metrics(ProjectModel model) {
        List<MethodMetrics> result = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                int lines = method.getRange().map(r -> r.end.line - r.begin.line + 1).orElse(0);
                result.add(new MethodMetrics("g" + model.idOf(method), method.getNameAsString(), file, lines, 0, 0, null));
            }
        });
        return result;
    }
}
