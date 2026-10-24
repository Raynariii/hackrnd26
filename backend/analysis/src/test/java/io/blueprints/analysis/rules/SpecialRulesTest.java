package io.blueprints.analysis.rules;

import io.blueprints.analysis.Analyzer;
import io.blueprints.analysis.Finding;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила, добавленные после корпусного прогона (2a-P2): дорогие операции в цикле и рекурсия
 * с экспоненциальным ростом. Каждое проверяется на срабатывание и на отсутствие ложного шума.
 */
class SpecialRulesTest {

    private static List<Finding> findings(String rule, String source) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", source)));
        return Analyzer.withDefaultRules().analyze(model).findings().stream()
                .filter(finding -> finding.rule().equals(rule))
                .toList();
    }

    @Test
    void findsPatternCompileInsideLoop() {
        String source = """
                class A {
                    boolean hasDigit(java.util.List<String> rows) {
                        for (String row : rows) {
                            if (java.util.regex.Pattern.compile("\\\\d+").matcher(row).find()) {
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """;

        List<Finding> found = findings("regex-compile-in-loop", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).severity().name()).isEqualTo("HIGH");
        assertThat(found.get(0).complexity().current()).isEqualTo("O(n)");
    }

    @Test
    void ignoresPatternCompiledOnce() {
        String source = """
                class A {
                    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\\\d+");

                    boolean hasDigit(java.util.List<String> rows) {
                        for (String row : rows) {
                            if (DIGITS.matcher(row).find()) {
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """;

        assertThat(findings("regex-compile-in-loop", source)).isEmpty();
    }

    @Test
    void findsStringFormatInsideLoop() {
        String source = """
                class A {
                    void print(java.util.List<Integer> values) {
                        for (Integer value : values) {
                            System.out.println(String.format("%5d", value));
                        }
                    }
                }
                """;

        List<Finding> found = findings("format-in-loop", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).fix().kind()).isEqualTo("string-builder");
    }

    @Test
    void ignoresStringFormatOutsideLoop() {
        String source = """
                class A {
                    String render(int value) {
                        return String.format("%5d", value);
                    }
                }
                """;

        assertThat(findings("format-in-loop", source)).isEmpty();
    }

    @Test
    void findsExponentialRecursion() {
        String source = """
                class A {
                    long fib(int n) {
                        if (n < 2) {
                            return n;
                        }
                        return fib(n - 1) + fib(n - 2);
                    }
                }
                """;

        List<Finding> found = findings("exponential-recursion", source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).complexity().current()).isEqualTo("O(2ⁿ)");
        assertThat(found.get(0).complexity().after()).isEqualTo("O(n)");
    }

    @Test
    void ignoresLinearRecursion() {
        String source = """
                class A {
                    long factorial(int n) {
                        if (n <= 1) {
                            return 1;
                        }
                        return n * factorial(n - 1);
                    }
                }
                """;

        assertThat(findings("exponential-recursion", source)).isEmpty();
    }

    @Test
    void ignoresNonRecursiveMethod() {
        String source = """
                class A {
                    long sum(long left, long right) {
                        return left + right;
                    }
                }
                """;

        assertThat(findings("exponential-recursion", source)).isEmpty();
    }
}
