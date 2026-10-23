package io.blueprints.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Тесты метрик: цикломатическая сложность, вложенность, оценка Big O и её формат. */
class MetricsTest {

    private static MethodDeclaration method(String source, String name) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", source)));
        return model.units().values().iterator().next().findAll(MethodDeclaration.class).stream()
                .filter(candidate -> candidate.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rendersComplexityForUser() {
        assertThat(BigO.constant()).hasToString("O(1)");
        assertThat(BigO.log()).hasToString("O(log n)");
        assertThat(BigO.of(1)).hasToString("O(n)");
        assertThat(BigO.of(2)).hasToString("O(n²)");
        assertThat(BigO.of(3)).hasToString("O(n³)");
        assertThat(BigO.nLogN()).hasToString("O(n log n)");
        assertThat(BigO.of(1).times(BigO.log())).hasToString("O(n log n)");
        assertThat(BigO.of(2).times(BigO.log())).hasToString("O(n² log n)");
        assertThat(BigO.of(5).times(BigO.of(2))).hasToString("O(n⁷)");
        assertThat(BigO.of(2).max(BigO.of(1))).isEqualTo(BigO.of(2));
    }

    @Test
    void countsCyclomaticComplexityAndNesting() {
        String source = """
                class A {
                    int f(int a, int b) {
                        int result = 0;
                        if (a > b) {
                            result = 1;
                        } else if (a == b) {
                            result = 2;
                        }
                        for (int i = 0; i < a; i++) {
                            result += i;
                        }
                        while (result > 500) {
                            result--;
                        }
                        return result;
                    }
                }
                """;

        MethodDeclaration method = method(source, "f");

        // 1 + if + else-if + for + while
        assertThat(MetricsCalculator.cyclomatic(method)).isEqualTo(5);
        // if → else-if = 2 уровня
        assertThat(MetricsCalculator.maxNesting(method)).isEqualTo(2);
    }

    @Test
    void estimatesSingleLoopAsLinear() {
        String source = """
                class A {
                    int sum(int[] values) {
                        int total = 0;
                        for (int value : values) {
                            total += value;
                        }
                        return total;
                    }
                }
                """;

        MethodDeclaration method = method(source, "sum");

        assertThat(new MetricsCalculator().complexity(method)).isEqualTo(BigO.of(1));
    }

    @Test
    void estimatesListLookupInsideLoopAsQuadratic() {
        String source = """
                class A {
                    boolean f(java.util.List<String> items, java.util.List<String> allowed) {
                        for (String item : items) {
                            if (allowed.contains(item)) {
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """;

        MethodDeclaration method = method(source, "f");

        assertThat(new MetricsCalculator().complexity(method)).isEqualTo(BigO.of(2));
    }

    @Test
    void estimatesNestedLoopsAsQuadratic() {
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

        MethodDeclaration method = method(source, "f");

        assertThat(new MetricsCalculator().complexity(method)).isEqualTo(BigO.of(2));
    }

    @Test
    void estimatesSortingAsLinearithmic() {
        String source = """
                class A {
                    void f(java.util.List<String> items) {
                        items.sort(null);
                    }
                }
                """;

        MethodDeclaration method = method(source, "f");

        assertThat(new MetricsCalculator().complexity(method)).isEqualTo(BigO.nLogN());
    }

    @Test
    void estimatesStraightLineAsConstant() {
        String source = """
                class A {
                    int f(int a, int b) {
                        int total = a + b;
                        return total;
                    }
                }
                """;

        MethodDeclaration method = method(source, "f");

        assertThat(new MetricsCalculator().complexity(method)).isEqualTo(BigO.constant());
    }
}
