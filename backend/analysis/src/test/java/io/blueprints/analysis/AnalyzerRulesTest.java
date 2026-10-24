package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты правил анализатора на Java-фрагментах: каждое правило проверяется на срабатывание
 * и на отсутствие ложного срабатывания.
 */
class AnalyzerRulesTest {

    private static AnalysisReport analyze(String source) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", source)));
        return Analyzer.withDefaultRules().analyze(model);
    }

    private static List<Finding> findings(String rule, String source) {
        return analyze(source).findings().stream().filter(finding -> finding.rule().equals(rule)).toList();
    }

    @Test
    void flagsNestedLoopsOverSameCollection() {
        String source = """
                class A {
                    void f(java.util.List<String> items) {
                        for (String a : items) {
                            for (String b : items) {
                                System.out.println(a + b);
                            }
                        }
                    }
                }
                """;

        List<Finding> found = findings("nested-loop-same-collection", source);

        assertThat(found).hasSize(1);
        Finding finding = found.get(0);
        assertThat(finding.severity()).isEqualTo(Severity.HIGH);
        assertThat(finding.complexity().current()).isEqualTo("O(n²)");
        assertThat(finding.complexity().after()).isEqualTo("O(n)");
        assertThat(finding.nodeIds()).hasSize(2);
        assertThat(finding.startLine()).isEqualTo(4);
        assertThat(finding.file()).isEqualTo("Main.java");
    }

    @Test
    void ignoresNestedLoopsOverDifferentCollections() {
        String source = """
                class A {
                    void f(java.util.List<String> first, java.util.List<String> second) {
                        for (String a : first) {
                            for (String b : second) {
                                System.out.println(a + b);
                            }
                        }
                    }
                }
                """;

        assertThat(findings("nested-loop-same-collection", source)).isEmpty();
    }

    @Test
    void flagsIndexLoopsOverSameArray() {
        String source = """
                class A {
                    void f(int[] values) {
                        for (int i = 0; i < values.length; i++) {
                            for (int j = 0; j < values.length; j++) {
                                if (values[i] == values[j]) {
                                    System.out.println(i);
                                }
                            }
                        }
                    }
                }
                """;

        assertThat(findings("nested-loop-same-collection", source)).hasSize(1);
    }

    @Test
    void flagsListLookupInsideLoop() {
        String source = """
                class A {
                    void f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            if (allowed.contains(name)) {
                                System.out.println(name);
                            }
                        }
                    }
                }
                """;

        List<Finding> found = findings("collection-lookup-in-loop", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).complexity().current()).isEqualTo("O(n²)");
        assertThat(found.get(0).complexity().after()).isEqualTo("O(n)");
        assertThat(found.get(0).fix().kind()).isEqualTo("index-by-key");
    }

    @Test
    void ignoresHashSetLookupInsideLoop() {
        String source = """
                class A {
                    void f(java.util.List<String> names, java.util.Set<String> allowed) {
                        for (String name : names) {
                            if (allowed.contains(name)) {
                                System.out.println(name);
                            }
                        }
                    }
                }
                """;

        assertThat(findings("collection-lookup-in-loop", source)).isEmpty();
    }

    @Test
    void flagsStringConcatenationInLoop() {
        String source = """
                class A {
                    String f(java.util.List<String> items) {
                        String result = "";
                        for (String item : items) {
                            result += item;
                        }
                        return result;
                    }
                }
                """;

        List<Finding> found = findings("string-concat-in-loop", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).complexity().current()).isEqualTo("O(n²)");
        assertThat(found.get(0).fix().kind()).isEqualTo("string-builder");
    }

    @Test
    void flagsSelfConcatenationAssignment() {
        String source = """
                class A {
                    String f(java.util.List<String> items) {
                        String result = "";
                        for (String item : items) {
                            result = result + item;
                        }
                        return result;
                    }
                }
                """;

        assertThat(findings("string-concat-in-loop", source)).hasSize(1);
    }

    @Test
    void ignoresStringBuilderInLoop() {
        String source = """
                class A {
                    String f(java.util.List<String> items) {
                        StringBuilder builder = new StringBuilder();
                        for (String item : items) {
                            builder.append(item);
                        }
                        return builder.toString();
                    }
                }
                """;

        assertThat(findings("string-concat-in-loop", source)).isEmpty();
    }

    @Test
    void flagsLinkedListIndexAccessInsideLoop() {
        String source = """
                class A {
                    void f(java.util.LinkedList<String> items) {
                        for (int i = 0; i < items.size(); i++) {
                            System.out.println(items.get(i));
                        }
                    }
                }
                """;

        List<Finding> found = findings("linked-list-index-access", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).complexity().current()).isEqualTo("O(n²)");
        assertThat(found.get(0).complexity().after()).isEqualTo("O(n)");
    }

    @Test
    void ignoresArrayListIndexAccessInsideLoop() {
        String source = """
                class A {
                    void f(java.util.ArrayList<String> items) {
                        for (int i = 0; i < items.size(); i++) {
                            System.out.println(items.get(i));
                        }
                    }
                }
                """;

        assertThat(findings("linked-list-index-access", source)).isEmpty();
    }

    @Test
    void flagsDeeplyNestedMethod() {
        String source = """
                class A {
                    void f(boolean a, boolean b, boolean c, boolean d, boolean e) {
                        if (a) {
                            if (b) {
                                if (c) {
                                    if (d) {
                                        if (e) {
                                            System.out.println(a);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """;

        List<Finding> found = findings("deep-nesting", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).severity()).isEqualTo(Severity.HIGH);
        assertThat(analyze(source).metrics()).singleElement()
                .satisfies(metrics -> {
                    assertThat(metrics.maxNesting()).isEqualTo(5);
                    assertThat(metrics.cyclomatic()).isEqualTo(6);
                });
    }

    @Test
    void flagsDuplicatedBlocks() {
        String source = """
                class A {
                    int f(int[] data) {
                        int total = 0;
                        for (int value : data) {
                            total += value;
                        }
                        total = total * 2;
                        total = total + 10;
                        System.out.println(total);
                        return total;
                    }

                    int g(int[] data) {
                        int total = 0;
                        for (int value : data) {
                            total += value;
                        }
                        total = total * 2;
                        total = total + 10;
                        System.out.println(total);
                        return total;
                    }
                }
                """;

        List<Finding> found = findings("duplicated-code", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).explanation()).contains("встречается 2 раза");
    }

    @Test
    void flagsEmptyCatch() {
        String source = """
                class A {
                    void f(String value) {
                        try {
                            Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                        }
                    }
                }
                """;

        List<Finding> found = findings("empty-catch", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).severity()).isEqualTo(Severity.HIGH);
        assertThat(found.get(0).fix().kind()).isEqualTo("log-or-rethrow");
    }

    @Test
    void ignoresCatchWithLogging() {
        String source = """
                class A {
                    void f(String value) {
                        try {
                            Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            System.out.println(e.getMessage());
                        }
                    }
                }
                """;

        assertThat(findings("empty-catch", source)).isEmpty();
    }

    @Test
    void flagsGodClass() {
        StringBuilder source = new StringBuilder("class A {\n");
        for (int i = 0; i < 21; i++) {
            source.append("    int method").append(i).append("() { return ").append(i).append("; }\n");
        }
        source.append("}\n");

        List<Finding> found = findings("god-class", source.toString());

        assertThat(found).hasSize(1);
        assertThat(found.get(0).explanation()).contains("21 метод");
    }

    @Test
    void flagsMagicNumberInCondition() {
        String source = """
                class A {
                    void f(java.util.List<String> items) {
                        if (items.size() > 100) {
                            System.out.println(items);
                        }
                    }
                }
                """;

        List<Finding> found = findings("magic-number", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).explanation()).contains("100");
    }

    @Test
    void ignoresTechnicalSmallNumbers() {
        String source = """
                class A {
                    void f(java.util.List<String> items) {
                        if (items.isEmpty()) {
                            return;
                        }
                        if (items.size() > 1) {
                            System.out.println(items);
                        }
                    }
                }
                """;

        assertThat(findings("magic-number", source)).isEmpty();
    }

    @Test
    void reportContainsMetricsForEveryMethod() {
        String source = """
                class A {
                    int add(int a, int b) {
                        return a + b;
                    }

                    int sum(int[] values) {
                        int total = 0;
                        for (int value : values) {
                            total += value;
                        }
                        return total;
                    }
                }
                """;

        AnalysisReport report = analyze(source);

        assertThat(report.metrics()).hasSize(2);
        assertThat(report.metrics()).extracting(MethodMetrics::name).containsExactly("add", "sum");
        assertThat(report.metrics().get(1).complexity()).isEqualTo("O(n)");
        assertThat(report.metrics().get(1).methodId()).startsWith("g");
    }
}
