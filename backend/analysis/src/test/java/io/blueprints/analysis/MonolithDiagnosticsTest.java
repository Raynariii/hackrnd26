package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прогон анализатора на мини-монолите из тестовых ресурсов: все заложенные дефекты найдены,
 * ссылки на граф и строки корректны, Big O по методам совпадает с ожидаемым.
 * Это проверка «диагноз проверяемый» из PLAN.md §1.4.
 */
class MonolithDiagnosticsTest {

    private static String fixture() throws Exception {
        try (InputStream stream = MonolithDiagnosticsTest.class.getResourceAsStream("/monolith/OrdersMonolith.java")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static AnalysisReport analyzeFixture() throws Exception {
        ProjectModel model = new JavaProjectParser()
                .parse(List.of(new SourceFile("OrdersMonolith.java", fixture())));
        return Analyzer.withDefaultRules().analyze(model);
    }

    @Test
    void findsEveryPlantedDefect() throws Exception {
        AnalysisReport report = analyzeFixture();

        assertThat(report.findings()).extracting(Finding::rule).contains(
                "nested-loop-same-collection",
                "collection-lookup-in-loop",
                "string-concat-in-loop",
                "linked-list-index-access",
                "deep-nesting",
                "duplicated-code",
                "empty-catch",
                "magic-number");
    }

    @Test
    void doesNotInventDefects() throws Exception {
        AnalysisReport report = analyzeFixture();

        assertThat(report.findings()).extracting(Finding::rule)
                .doesNotContain("long-method", "god-class");
    }

    @Test
    void everyFindingPointsToCodeAndGraphNodes() throws Exception {
        AnalysisReport report = analyzeFixture();

        assertThat(report.findings()).isNotEmpty();
        assertThat(report.findings()).allSatisfy(finding -> {
            assertThat(finding.startLine()).isPositive();
            assertThat(finding.endLine()).isGreaterThanOrEqualTo(finding.startLine());
            assertThat(finding.nodeIds()).isNotEmpty();
            assertThat(finding.nodeIds()).allSatisfy(nodeId -> assertThat(nodeId).startsWith("g"));
            assertThat(finding.file()).isEqualTo("OrdersMonolith.java");
            assertThat(finding.severity()).isNotNull();
            assertThat(finding.explanation()).isNotBlank();
        });
    }

    @Test
    void estimatesComplexityOfEachMethod() throws Exception {
        AnalysisReport report = analyzeFixture();
        Map<String, String> complexity = report.metrics().stream()
                .collect(Collectors.toMap(MethodMetrics::name, MethodMetrics::complexity));

        assertThat(complexity).containsEntry("printSummary", "O(1)");
        assertThat(complexity).containsEntry("parseAmounts", "O(n)");
        assertThat(complexity).containsEntry("processOrders", "O(n²)");
        assertThat(complexity).containsEntry("scanQueue", "O(n²)");
        assertThat(complexity).containsEntry("duplicateCheck", "O(n³)");
    }

    @Test
    void reportsCyclomaticComplexityOfDeepMethod() throws Exception {
        AnalysisReport report = analyzeFixture();
        MethodMetrics classify = report.metrics().stream()
                .filter(metrics -> metrics.name().equals("classify"))
                .findFirst()
                .orElseThrow();

        assertThat(classify.maxNesting()).isEqualTo(5);
        assertThat(classify.cyclomatic()).isEqualTo(6);
    }
}
