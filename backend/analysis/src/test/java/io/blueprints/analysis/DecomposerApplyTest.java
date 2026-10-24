package io.blueprints.analysis;

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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Тесты применения декомпозиции (v1): static-методы переносятся в Parser/Analyzer, вызовы
 * и ссылки на вложенные типы квалифицируются, а результат компилируется настоящим javac.
 * Поведение (дифф-тесты) проверяет quality gate роли 4 — здесь только код.
 */
class DecomposerApplyTest {

    private static Decomposer.Applied apply(String source) {
        return new Decomposer().apply(List.of(new SourceFile("Monolith.java", source)));
    }

    private static String file(Decomposer.Applied applied, String path) {
        return applied.files().stream()
                .filter(sourceFile -> sourceFile.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет файла " + path + " в " + applied.files().stream()
                        .map(SourceFile::path).toList()))
                .content();
    }

    /** Компилирует весь набор файлов настоящим javac: классы не публичные, имя файла = имя типа. */
    private static boolean compiles(List<SourceFile> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = new ArrayList<>();
        for (SourceFile file : files) {
            String[] parts = file.path().replace('\\', '/').split("/");
            String name = parts[parts.length - 1];
            String typeName = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
            units.add(new SimpleJavaFileObject(URI.create("string:///" + typeName + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return file.content();
                }
            });
        }
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Path output = Files.createTempDirectory("blueprints-decompose");
            boolean compiled = compiler.getTask(new StringWriter(), manager, diagnostics,
                    List.of("-proc:none", "-d", output.toString()), null, units).call();
            if (!compiled) {
                diagnostics.getDiagnostics().forEach(diagnostic -> System.out.println("javac: " + diagnostic));
            }
            return compiled;
        } catch (Exception e) {
            throw new IllegalStateException("javac недоступен: " + e.getMessage(), e);
        }
    }

    private static final String STATIC_MONOLITH = """
            class Monolith {
                public static void main(String[] args) {
                    String line = readLine("a b c");
                    System.out.println("words=" + countWords(line) + " valid=" + validateOrder(line));
                }

                static String readLine(String raw) {
                    return raw.trim();
                }

                static String parseLine(String raw) {
                    return raw.replace(",", " ");
                }

                static int countWords(String line) {
                    return line.isEmpty() ? 0 : line.split(" ").length;
                }

                static boolean validateOrder(String line) {
                    return !line.contains("#");
                }

                static String reportStats(int words) {
                    return "words=" + words;
                }
            }
            """;

    @Test
    void movesStaticMethodsIntoNewClassesAndCompiles() {
        Decomposer.Applied applied = apply(STATIC_MONOLITH);

        assertThat(applied.files()).extracting(SourceFile::path)
                .contains("Monolith.java", "Parser.java", "Analyzer.java");
        String monolith = file(applied, "Monolith.java");
        String parser = file(applied, "Parser.java");
        String analyzer = file(applied, "Analyzer.java");

        assertThat(parser).contains("final class Parser").contains("static String readLine(String raw)")
                .contains("static String parseLine(String raw)");
        assertThat(analyzer).contains("final class Analyzer").contains("static int countWords(String line)")
                .contains("static boolean validateOrder(String line)").contains("static String reportStats(int words)");
        assertThat(monolith).contains("static void main").doesNotContain("static String readLine(")
                .doesNotContain("static int countWords(");
        assertThat(monolith).contains("Parser.readLine(").contains("Analyzer.countWords(").contains("Analyzer.validateOrder(");
        assertThat(compiles(applied.files())).isTrue();
        assertThat(applied.description()).contains("5 static-методов").contains("Parser").contains("Analyzer");
    }

    @Test
    void qualifiesCallsBetweenMovedClassesAndCompiles() {
        String source = """
                class Monolith {
                    public static void main(String[] args) {
                        System.out.println(report(parse("1 2 3")));
                    }

                    static String load(String raw) {
                        return raw.trim();
                    }

                    static int parse(String raw) {
                        return analyze(load(raw).split(" "));
                    }

                    static int analyze(String[] parts) {
                        return parts.length;
                    }

                    static String report(int n) {
                        return "n=" + n;
                    }

                    static String format(String text) {
                        return "[" + text + "]";
                    }
                }
                """;

        Decomposer.Applied applied = apply(source);

        String parser = file(applied, "Parser.java");
        String monolith = file(applied, "Monolith.java");
        assertThat(parser).contains("Analyzer.analyze(");
        assertThat(monolith).contains("Parser.parse(").contains("Analyzer.report(");
        assertThat(compiles(applied.files())).isTrue();
    }

    @Test
    void keepsMainFieldsAndInstanceMethodsInOriginalClass() {
        String source = """
                class Monolith {
                    private static int calls;
                    private int state = 1;

                    public static void main(String[] args) {
                        System.out.println(read(" a ") + count("a b"));
                    }

                    static String read(String raw) {
                        return raw.trim();
                    }

                    static String parse(String raw) {
                        return raw.toLowerCase();
                    }

                    static int count(String line) {
                        return line.split(" ").length;
                    }

                    static boolean validate(String line) {
                        return !line.isBlank();
                    }

                    int state() {
                        return state;
                    }

                    static int bump() {
                        calls++;
                        return calls;
                    }
                }
                """;

        Decomposer.Applied applied = apply(source);
        String monolith = file(applied, "Monolith.java");

        assertThat(monolith).contains("static void main").contains("int state()").contains("static int bump()")
                .contains("static int calls").doesNotContain("static String read(");
        assertThat(applied.description()).contains("Точка входа");
        assertThat(compiles(applied.files())).isTrue();
    }

    @Test
    void qualifiesNestedTypeReferencesAndCompiles() {
        String source = """
                class Monolith {
                    record Order(String id) {
                    }

                    public static void main(String[] args) {
                        System.out.println(show(new Order("a")));
                    }

                    static String show(Order order) {
                        return read(order);
                    }

                    static String read(Order order) {
                        return order.id();
                    }

                    static String describe(Order order) {
                        return order.id().toUpperCase();
                    }

                    static void log(Order order) {
                        System.out.println(order);
                    }

                    static String details(Order order) {
                        return order.id() + "!";
                    }
                }
                """;

        Decomposer.Applied applied = apply(source);

        assertThat(file(applied, "Parser.java")).contains("Monolith.Order");
        assertThat(file(applied, "Utils.java")).contains("Monolith.Order");
        assertThat(compiles(applied.files())).isTrue();
    }

    @Test
    void movesStaticFieldUsedOnlyByOneGroup() {
        String source = """
                class Monolith {
                    private static int parsed;

                    public static void main(String[] args) {
                        System.out.println(read(" 7 ") + count("a b"));
                    }

                    static String read(String raw) {
                        parsed++;
                        return raw.trim();
                    }

                    static String parse(String raw) {
                        parsed++;
                        return raw.replace(",", " ");
                    }

                    static int count(String line) {
                        return line.split(" ").length;
                    }

                    static boolean validateOrder(String line) {
                        return !line.isBlank();
                    }

                    static String report(int n) {
                        return "n=" + n;
                    }
                }
                """;

        Decomposer.Applied applied = apply(source);

        assertThat(file(applied, "Parser.java")).contains("static int parsed").contains("parsed++");
        assertThat(file(applied, "Monolith.java")).doesNotContain("static int parsed");
        assertThat(applied.description()).contains("static-поле");
        assertThat(compiles(applied.files())).isTrue();
    }

    @Test
    void keepsSharedFieldAndItsUsersInOriginalClass() {
        String source = """
                class Monolith {
                    private static int counter;

                    public static void main(String[] args) {
                        System.out.println(read(" a ") + count("a b") + validateOrder("") + report(1));
                    }

                    static String read(String raw) {
                        counter++;
                        return raw.trim();
                    }

                    static String parse(String raw) {
                        return raw.replace(",", " ");
                    }

                    static int count(String line) {
                        counter++;
                        return line.split(" ").length;
                    }

                    static boolean validateOrder(String line) {
                        return !line.isBlank();
                    }

                    static String report(int n) {
                        return "n=" + n;
                    }
                }
                """;

        Decomposer.Applied applied = apply(source);
        String monolith = file(applied, "Monolith.java");

        assertThat(monolith).contains("static int counter").contains("static String read(").contains("static int count(");
        assertThat(applied.files()).extracting(SourceFile::path).contains("Analyzer.java");
        assertThat(compiles(applied.files())).isTrue();
    }

    @Test
    void refusesWhenOnlyInstanceMethods() {
        String source = """
                class Monolith {
                    void readFile(String path) {
                    }

                    String parseLine(String line) {
                        return line;
                    }

                    void loadInput() {
                    }

                    int analyzeOrders(java.util.List<String> orders) {
                        return orders.size();
                    }

                    boolean validateOrder(String order) {
                        return order != null;
                    }

                    void reportStats() {
                    }

                    void sortOrders(java.util.List<String> orders) {
                        orders.sort(null);
                    }

                    void deduplicate(java.util.List<String> orders) {
                    }
                }
                """;

        assertThatThrownBy(() -> apply(source))
                .isInstanceOf(FixNotApplicableException.class)
                .hasMessageContaining("static");
    }
}
