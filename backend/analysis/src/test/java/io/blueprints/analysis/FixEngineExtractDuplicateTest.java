package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Дубль → общий метод через {@code GraphOperations.extractMethod} ядра (2b, P2).
 *
 * <p>Исправление работает только на текстуально идентичных вхождениях: тогда вызов из второго места
 * корректен без вывода типов заново. Всё остальное — честный отказ, в том числе отказ ядра
 * (return/break наружу, несколько выходных переменных, {@code var}).
 */
class FixEngineExtractDuplicateTest {

    /** Пять операторов, читающих только параметр: выделение даёт void-метод без выходных значений. */
    private static final String DUPLICATE = """
            class A {
                void reportFirst(java.util.List<String> rows) {
                    System.out.println("start");
                    System.out.println(rows.size());
                    System.out.println("middle");
                    System.out.println(rows.isEmpty());
                    System.out.println("end");
                }

                void reportSecond(java.util.List<String> rows) {
                    System.out.println("start");
                    System.out.println(rows.size());
                    System.out.println("middle");
                    System.out.println(rows.isEmpty());
                    System.out.println("end");
                }
            }
            """;

    private static List<Finding> findings(String source) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", source)));
        return Analyzer.withDefaultRules().analyze(model).findings().stream()
                .filter(finding -> finding.rule().equals("duplicated-code"))
                .toList();
    }

    private static FixEngine.Applied apply(String source) {
        List<SourceFile> files = List.of(new SourceFile("Main.java", source));
        ProjectModel model = new JavaProjectParser().parse(files);
        Finding finding = new Analyzer(Analyzer.defaultRules()).analyze(model).findings().stream()
                .filter(candidate -> candidate.rule().equals("duplicated-code"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет находки duplicated-code"));
        return new FixEngine().apply(files, finding.id());
    }

    private static boolean compiles(String code) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///A.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Path output = Files.createTempDirectory("blueprints-extract");
            boolean compiled = compiler.getTask(new StringWriter(), files, diagnostics,
                    List.of("-proc:none", "-d", output.toString()), null, List.of(unit)).call();
            if (!compiled) {
                diagnostics.getDiagnostics().forEach(diagnostic -> System.out.println("javac: " + diagnostic));
            }
            return compiled;
        } catch (Exception e) {
            throw new IllegalStateException("javac недоступен: " + e.getMessage(), e);
        }
    }

    @Test
    void extractsIdenticalDuplicateIntoSharedMethod() throws Exception {
        List<Finding> found = findings(DUPLICATE);
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).fix().kind()).isEqualTo("extract-duplicate");
        assertThat(found.get(0).fix().autoApplicable()).isTrue();

        String code = apply(DUPLICATE).files().get(0).content();

        // Оба места печати свернулись в один вызов: текст дубля остался только в общем методе.
        assertThat(code).contains("private void extracted(");
        assertThat(code.split("extracted", -1)).hasSize(4);
        assertThat(code.split("System.out.println", -1)).hasSize(6);
        assertThat(compiles(code)).isTrue();
    }

    @Test
    void refusesWhenCoreMustReturnValue() {
        String source = """
                class A {
                    int computeFirst(int[] data) {
                        System.out.println("start");
                        System.out.println(data.length);
                        int total = 0;
                        for (int value : data) {
                            total += value;
                        }
                        System.out.println("end");
                        return total;
                    }

                    int computeSecond(int[] data) {
                        System.out.println("start");
                        System.out.println(data.length);
                        int total = 0;
                        for (int value : data) {
                            total += value;
                        }
                        System.out.println("end");
                        return total;
                    }
                }
                """;

        List<Finding> found = findings(source);
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).fix().autoApplicable()).isFalse();
        assertThatThrownBy(() -> apply(source)).isInstanceOf(FixNotApplicableException.class);
    }

    @Test
    void nearDuplicatesStayManual() {
        String source = """
                class A {
                    int totalForCity(java.util.List<Order> orders, String city) {
                        int total = 0;
                        for (Order order : orders) {
                            if (order.cancelled) {
                                continue;
                            }
                            if (!order.city.equals(city)) {
                                continue;
                            }
                            total += order.quantity;
                        }
                        return total;
                    }

                    int totalForCategory(java.util.List<Order> orders, String category) {
                        int total = 0;
                        for (Order order : orders) {
                            if (order.cancelled) {
                                continue;
                            }
                            for (Line line : order.lines) {
                                if (!line.category.equals(category)) {
                                    continue;
                                }
                                total += line.quantity;
                            }
                        }
                        return total;
                    }
                }
                """;

        List<Finding> found = findings(source);
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).fix().kind()).isEqualTo("extract-method");
        assertThat(found.get(0).fix().autoApplicable()).isFalse();
    }
}
