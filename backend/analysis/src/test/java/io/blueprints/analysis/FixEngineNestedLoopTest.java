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
 * Авто-исправление линейного поиска во вложенных циклах (2b): {@code for (a : xs) for (b : ys) if
 * (a.equals(b)) {…; break;}} → индекс {@code HashSet} и один цикл.
 *
 * <p>Правило {@code nested-loop-linear-search} намеренно узкое: там, где форма небезопасна (нет
 * {@code break}, сравнение не через {@code equals}, элемент используется в теле), находки нет вовсе.
 * Отдельно проверяются отказы фиксера, когда правило сработало, но правка изменила бы поведение.
 */
class FixEngineNestedLoopTest {

    private static final String PATTERN = """
            class A {
                void f(java.util.List<String> names, java.util.List<String> allowed) {
                    for (String name : names) {
                        for (String candidate : allowed) {
                            if (name.equals(candidate)) {
                                System.out.println(name);
                                break;
                            }
                        }
                    }
                }
            }
            """;

    private static List<Finding> findings(String source) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", source)));
        return Analyzer.withDefaultRules().analyze(model).findings().stream()
                .filter(candidate -> candidate.rule().equals("nested-loop-linear-search"))
                .toList();
    }

    private static FixEngine.Applied apply(String source) {
        List<SourceFile> files = List.of(new SourceFile("Main.java", source));
        ProjectModel model = new JavaProjectParser().parse(files);
        Finding finding = new Analyzer(Analyzer.defaultRules()).analyze(model).findings().stream()
                .filter(candidate -> candidate.rule().equals("nested-loop-linear-search"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет находки nested-loop-linear-search"));
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
            Path output = Files.createTempDirectory("blueprints-nested");
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
    void replacesInnerLoopWithHashSetLookup() {
        FixEngine.Applied applied = apply(PATTERN);
        String code = applied.files().get(0).content();

        assertThat(code).contains("var _index = new java.util.HashSet<>(allowed);");
        assertThat(code).contains("if (_index.contains(name)) {");
        assertThat(code).contains("System.out.println(name);");
        assertThat(code).doesNotContain("for (String candidate : allowed)");
        assertThat(code).doesNotContain("break;");
        assertThat(compiles(code)).isTrue();
    }

    @Test
    void ruleMarksPatternAsAutoApplicable() {
        List<Finding> found = findings(PATTERN);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).fix().kind()).isEqualTo("nested-loop-index");
        assertThat(found.get(0).fix().autoApplicable()).isTrue();
        assertThat(found.get(0).complexity().current()).isEqualTo("O(n²)");
        assertThat(found.get(0).complexity().after()).isEqualTo("O(n)");
    }

    @Test
    void ruleIgnoresLoopWithoutBreak() {
        String source = """
                class A {
                    void f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            for (String candidate : allowed) {
                                if (name.equals(candidate)) {
                                    System.out.println(name);
                                }
                            }
                        }
                    }
                }
                """;

        assertThat(findings(source)).isEmpty();
    }

    @Test
    void ruleIgnoresNonEqualityCondition() {
        String source = """
                class A {
                    void f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            for (String candidate : allowed) {
                                if (name.length() == candidate.length()) {
                                    System.out.println(name);
                                    break;
                                }
                            }
                        }
                    }
                }
                """;

        assertThat(findings(source)).isEmpty();
    }

    @Test
    void ruleIgnoresNestedLoopsOverSameCollection() {
        String source = """
                class A {
                    void f(java.util.List<String> names) {
                        for (String name : names) {
                            for (String candidate : names) {
                                if (name.equals(candidate)) {
                                    System.out.println(name);
                                    break;
                                }
                            }
                        }
                    }
                }
                """;

        assertThat(findings(source)).isEmpty();
    }

    @Test
    void fixerRefusesWhenCollectionIsNotAVariable() {
        String source = """
                class A {
                    java.util.List<String> allowed = new java.util.ArrayList<>();

                    void f(java.util.List<String> names) {
                        for (String name : names) {
                            for (String candidate : this.allowed) {
                                if (name.equals(candidate)) {
                                    System.out.println(name);
                                    break;
                                }
                            }
                        }
                    }
                }
                """;

        assertThat(findings(source)).hasSize(1);
        assertThatThrownBy(() -> apply(source))
                .isInstanceOf(FixNotApplicableException.class)
                .hasMessageContaining("не переменная");
    }

    @Test
    void fixerRefusesWhenCollectionIsMutatedInLoop() {
        String source = """
                class A {
                    void f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            for (String candidate : allowed) {
                                if (name.equals(candidate)) {
                                    allowed.remove(candidate);
                                    break;
                                }
                            }
                        }
                    }
                }
                """;

        assertThatThrownBy(() -> apply(source))
                .isInstanceOf(FixNotApplicableException.class)
                .hasMessageContaining("меняется внутри цикла");
    }
}
