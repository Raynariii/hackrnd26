package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Анализ не должен падать на современной Java (бонусный scope PLAN.md §5: record, enum, sealed,
 * дженерики, лямбды, Stream API, switch-выражения). Правила обязаны либо дать находку, либо
 * промолчать — но не бросить исключение.
 */
class ModernJavaRobustnessTest {

    private static AnalysisReport analyze(String source) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Modern.java", source)));
        assertThat(model.hasErrors()).isFalse();
        return Analyzer.withDefaultRules().analyze(model);
    }

    @Test
    void analyzesRecordsEnumsSealedTypesAndGenerics() {
        String source = """
                import java.util.List;

                public class Modern {
                    record Order(String id, int amount) { }

                    enum Status { NEW, PAID, CANCELLED }

                    sealed interface Event permits Created, Paid { }
                    record Created(Order order) implements Event { }
                    record Paid(Order order) implements Event { }

                    String describe(Order order) {
                        return switch (order.amount()) {
                            case 0 -> "empty";
                            default -> order.amount() > 1000 ? "big" : "small";
                        };
                    }

                    List<Order> paid(List<Order> orders, Status status) {
                        return orders.stream()
                                .filter(order -> status == Status.PAID)
                                .toList();
                    }
                }
                """;

        AnalysisReport report = analyze(source);

        assertThat(report.metrics()).extracting(MethodMetrics::name).contains("describe", "paid");
        assertThat(report.metrics()).allSatisfy(metrics -> assertThat(metrics.complexity()).isNotNull());
    }

    @Test
    void analyzesLambdasAndStreams() {
        String source = """
                import java.util.Comparator;
                import java.util.List;
                import java.util.Map;
                import java.util.stream.Collectors;

                public class Modern {
                    void process(List<String> items) {
                        Map<Integer, List<String>> byLength = items.stream()
                                .collect(Collectors.groupingBy(String::length));
                        List<String> sorted = items.stream()
                                .sorted(Comparator.naturalOrder())
                                .toList();
                        Runnable task = () -> System.out.println(sorted);
                        task.run();
                        System.out.println(byLength);
                    }
                }
                """;

        AnalysisReport report = analyze(source);

        assertThat(report.metrics()).singleElement()
                .satisfies(metrics -> assertThat(metrics.name()).isEqualTo("process"));
        assertThat(report.findings()).allSatisfy(finding -> assertThat(finding.startLine()).isPositive());
    }

    @Test
    void findsDefectsInsideSwitchAndTryWithResources() {
        String source = """
                import java.util.List;

                public class Modern {
                    int handle(List<String> rows) {
                        int total = 0;
                        for (String row : rows) {
                            try (var reader = new java.io.StringReader(row)) {
                                switch (row) {
                                    case "a" -> total += 1;
                                    case "b" -> total += 2;
                                    default -> total += reader.hashCode();
                                }
                            } catch (java.io.IOException e) {
                            }
                        }
                        if (total > 100) {
                            System.out.println(total);
                        }
                        return total;
                    }
                }
                """;

        AnalysisReport report = analyze(source);

        assertThat(report.findings()).extracting(Finding::rule)
                .contains("empty-catch", "magic-number");
    }
}
