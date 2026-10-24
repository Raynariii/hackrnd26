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
 * Тесты авто-исправлений: меняется AST, печатается код, поведение сохраняется.
 *
 * <p>Проверяем три уровня: находка помечена как автоматически применимая, исправленный код
 * разбирается заново и — главное — <b>компилируется javac</b>. Второй шаг quality gate
 * (дифф-тесты поведения) выполняет роль 4 в песочнице.
 */
class FixEngineTest {

    private static Applied apply(String source, String rule) {
        List<SourceFile> files = List.of(new SourceFile("Main.java", source));
        ProjectModel model = new JavaProjectParser().parse(files);
        Finding finding = new Analyzer(Analyzer.defaultRules()).analyze(model).findings().stream()
                .filter(candidate -> candidate.rule().equals(rule))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет находки правила " + rule));
        return new Applied(new FixEngine().apply(files, finding.id()), finding);
    }

    /** Применённое исправление и напечатанный код. */
    private record Applied(FixEngine.Applied result, Finding finding) {
        String code() {
            return result.files().stream().filter(file -> file.path().equals("Main.java"))
                    .findFirst().orElseThrow().content();
        }
    }

    private static boolean reparses(String code) {
        return !new JavaProjectParser().parse(List.of(new SourceFile("Main.java", code))).hasErrors();
    }

    /** Компилирует исходник настоящим javac: класс должен называться A. */
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
            Path output = Files.createTempDirectory("blueprints-fix");
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
    void buildsHashSetInsteadOfListLookup() {
        String source = """
                class A {
                    boolean f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            if (allowed.contains(name)) {
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """;

        Applied applied = apply(source, "collection-lookup-in-loop");
        String code = applied.code();

        assertThat(code).contains("new java.util.HashSet<>(allowed)");
        assertThat(code).contains("_index.contains(name)");
        assertThat(reparses(code)).isTrue();
        assertThat(compiles(code)).isTrue();
        assertThat(applied.finding().fix().autoApplicable()).isTrue();
    }

    @Test
    void replacesConcatenationWithStringBuilder() {
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

        String code = apply(source, "string-concat-in-loop").code();

        assertThat(code).contains("var _sb = new java.lang.StringBuilder();");
        assertThat(code).contains("_sb.append(item)");
        assertThat(code).contains("result = _sb.toString();");
        assertThat(reparses(code)).isTrue();
        assertThat(compiles(code)).isTrue();
    }

    @Test
    void extractsConstantForMagicNumber() {
        String source = """
                class A {
                    void f(java.util.List<String> items) {
                        if (items.size() > 100) {
                            System.out.println(items);
                        }
                    }
                }
                """;

        String code = apply(source, "magic-number").code();

        assertThat(code).contains("private static final int LIMIT_100 = 100;");
        assertThat(code).contains("LIMIT_100");
        assertThat(reparses(code)).isTrue();
        assertThat(compiles(code)).isTrue();
    }

    @Test
    void replacesIndexAccessWithForEach() {
        String source = """
                class A {
                    void f(java.util.LinkedList<String> items) {
                        for (int i = 0; i < items.size(); i++) {
                            System.out.println(items.get(i));
                        }
                    }
                }
                """;

        String code = apply(source, "linked-list-index-access").code();

        assertThat(code).contains("for (var _item : items)");
        assertThat(code).contains("System.out.println(_item)");
        assertThat(code).doesNotContain("items.get(i)");
        assertThat(reparses(code)).isTrue();
        assertThat(compiles(code)).isTrue();
    }

    @Test
    void refusesWhenStringIsReadInsideLoop() {
        String source = """
                class A {
                    void f(java.util.List<String> items) {
                        String result = "";
                        for (String item : items) {
                            result += item;
                            System.out.println(result);
                        }
                    }
                }
                """;

        assertThatThrownBy(() -> apply(source, "string-concat-in-loop"))
                .isInstanceOf(FixNotApplicableException.class)
                .hasMessageContaining("читается внутри цикла");
    }

    @Test
    void refusesWhenCollectionIsMutatedInsideLoop() {
        String source = """
                class A {
                    boolean f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            if (allowed.contains(name)) {
                                allowed.remove(name);
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """;

        assertThatThrownBy(() -> apply(source, "collection-lookup-in-loop"))
                .isInstanceOf(FixNotApplicableException.class)
                .hasMessageContaining("меняется внутри цикла");
    }

    @Test
    void refusesManualOnlyFix() {
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

        assertThatThrownBy(() -> apply(source, "nested-loop-same-collection"))
                .isInstanceOf(FixNotApplicableException.class)
                .hasMessageContaining("требует ручной правки");
    }

    @Test
    void refusesStaleFindingId() {
        List<SourceFile> files = List.of(new SourceFile("Main.java", "class A { int f() { return 1; } }"));

        assertThatThrownBy(() -> new FixEngine().apply(files, "nested-loop-same-collection:g42"))
                .isInstanceOf(FixNotApplicableException.class)
                .hasMessageContaining("не актуальна");
    }
}
