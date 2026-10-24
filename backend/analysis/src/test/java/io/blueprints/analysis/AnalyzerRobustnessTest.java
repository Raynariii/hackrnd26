package io.blueprints.analysis;

import io.blueprints.analysis.rules.LongMethodRule;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Устойчивость диагностики: одно упавшее правило или неразобранный файл не должны обнулять
 * панель «Проблемы» — иначе на первом же монолите с незнакомой конструкцией демо покажет
 * «проблем нет».
 */
class AnalyzerRobustnessTest {

    private static final String SOURCE = """
            class A {
                void f() {
                    int x = 0;
                    x++;
                }
            }
            """;

    @Test
    void oneBrokenRuleDoesNotHideFindingsOfOthers() {
        Rule broken = new Rule() {
            @Override
            public String id() {
                return "broken";
            }

            @Override
            public List<Finding> check(ProjectModel model) {
                throw new IllegalStateException("правило не осилило конструкцию");
            }
        };
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("A.java", SOURCE)));

        AnalysisReport report = new Analyzer(List.of(broken, new LongMethodRule(1))).analyze(model);

        assertThat(report.findings()).extracting(Finding::rule).contains("long-method");
        assertThat(report.metrics()).hasSize(1);
    }

    @Test
    void unparsableFileDoesNotBreakAnalysisOfOtherFiles() {
        ProjectModel model = new JavaProjectParser().parse(List.of(
                new SourceFile("Bad.java", "class Bad { void f( }"),
                new SourceFile("Good.java", SOURCE)));

        AnalysisReport report = Analyzer.withDefaultRules().analyze(model);

        assertThat(report.metrics()).extracting(MethodMetrics::name).contains("f");
    }

    @Test
    void emptyProjectProducesEmptyReport() {
        ProjectModel model = new JavaProjectParser().parse(List.of());

        assertThatCode(() -> Analyzer.withDefaultRules().analyze(model)).doesNotThrowAnyException();
    }
}
