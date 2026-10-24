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
 * Дублирование: точные копии и почти-копии, которых не было видно до прогона по корпусу.
 *
 * <p>На монолитах аналитику нужны оба случая: копия может отличаться фильтром на уровень выше
 * (методы {@code totalQuantityForCity}/{@code totalQuantityForCategory}) или повторяться ветвями
 * внутри одного метода ({@code if/else-if/else} со одинаковыми телами).
 */
class DuplicateCodeRuleTest {

    private static List<Finding> duplicates(String source) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", source)));
        return Analyzer.withDefaultRules().analyze(model).findings().stream()
                .filter(finding -> finding.rule().equals("duplicated-code"))
                .toList();
    }

    @Test
    void findsNearCopyMethodsThatDifferByFilterLevel() {
        String source = """
                class Main {
                    int totalForCity(String city) {
                        int total = 0;
                        for (Order order : orders) {
                            if (order.cancelled) {
                                continue;
                            }
                            if (!order.customer.city.equals(city)) {
                                continue;
                            }
                            for (OrderLine line : order.lines) {
                                total += line.quantity;
                            }
                        }
                        return total;
                    }

                    int totalForCategory(String category) {
                        int total = 0;
                        for (Order order : orders) {
                            if (order.cancelled) {
                                continue;
                            }
                            for (OrderLine line : order.lines) {
                                if (!line.product.category.equals(category)) {
                                    continue;
                                }
                                total += line.quantity;
                            }
                        }
                        return total;
                    }
                }
                """;

        List<Finding> found = duplicates(source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).explanation())
                .contains("totalForCity()")
                .contains("totalForCategory()")
                .contains("100%");
    }

    @Test
    void findsDuplicateBranchesInsideOneMethod() {
        String source = """
                class Main {
                    long fineFor(String type, int overdueDays) {
                        long fine;
                        if (type.equals("STUDENT")) {
                            fine = overdueDays * 500L;
                            if (fine > 30000) {
                                fine = 30000;
                            }
                        } else if (type.equals("SENIOR")) {
                            fine = overdueDays * 250L;
                            if (fine > 30000) {
                                fine = 30000;
                            }
                        } else {
                            fine = overdueDays * 1000L;
                            if (fine > 30000) {
                                fine = 30000;
                            }
                        }
                        return fine;
                    }
                }
                """;

        List<Finding> found = duplicates(source);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).explanation()).contains("ветви условия");
    }

    @Test
    void keepsExactCopiesWorking() {
        String source = """
                class Main {
                    int first(int[] data) {
                        int total = 0;
                        for (int value : data) {
                            total += value;
                        }
                        total = total * 2;
                        total = total + 10;
                        System.out.println(total);
                        return total;
                    }

                    int second(int[] data) {
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

        List<Finding> found = duplicates(source);

        assertThat(found).isNotEmpty();
        assertThat(found.get(0).title()).isEqualTo("Дублирование кода");
    }

    @Test
    void ignoresUnrelatedMethods() {
        String source = """
                class Main {
                    String money(long kopecks) {
                        return String.format("%d,%02d", kopecks / 100, kopecks % 100);
                    }

                    void printTitle(String title) {
                        System.out.println("== " + title + " ==");
                        System.out.println();
                    }
                }
                """;

        assertThat(duplicates(source)).isEmpty();
    }

    @Test
    void ignoresTinyRepeatedGuards() {
        String source = """
                class Main {
                    void run(java.util.List<Order> orders) {
                        for (Order order : orders) {
                            if (order.cancelled) {
                                continue;
                            }
                        }
                        for (Order order : orders) {
                            if (order.cancelled) {
                                continue;
                            }
                        }
                    }
                }
                """;

        assertThat(duplicates(source)).isEmpty();
    }
}
