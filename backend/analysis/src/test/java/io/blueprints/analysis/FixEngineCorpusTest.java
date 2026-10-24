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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Каждое рекламируемое исправление действительно применяется и компилируется» — на реальном коде,
 * а не на вырожденных сниппетах.
 *
 * <p>Берём мини-монолит из тестовых ресурсов и все примеры {@code corpus/} (если роль 4 их положила,
 * PR #28), для каждого вида исправления применяем по одной применимой находке и компилируем результат
 * настоящим {@code javac}. Это ловит случаи, когда фиксер проходит свои проверки, но печатает
 * некомпилируемый код — ровно то, что увидит пользователь по кнопке «Применить».
 */
class FixEngineCorpusTest {

    private static final Path CORPUS = Path.of("..", "..", "corpus");
    private static final Path FIXTURE = Path.of("src", "test", "resources", "monolith", "OrdersMonolith.java");

    @Test
    void everyAutoApplicableKindAppliesAndCompiles() throws Exception {
        List<Path> sources = new ArrayList<>();
        sources.add(FIXTURE);
        try (Stream<Path> walk = Files.walk(CORPUS)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith("Main.java"))
                    .sorted()
                    .forEach(sources::add);
        } catch (java.io.IOException ignored) {
            // корпуса нет — проверяем только мини-монолит
        }

        int applied = 0;
        for (Path path : sources) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            String className = path.getFileName().toString().replace(".java", "");
            List<SourceFile> files = List.of(new SourceFile(path.getFileName().toString(), source));
            ProjectModel model = new JavaProjectParser().parse(files);
            List<Finding> findings = Analyzer.withDefaultRules().analyze(model).findings();

            Map<String, Finding> firstPerKind = new LinkedHashMap<>();
            for (Finding finding : findings) {
                if (finding.fix() != null && finding.fix().autoApplicable()) {
                    firstPerKind.putIfAbsent(finding.fix().kind(), finding);
                }
            }
            for (Map.Entry<String, Finding> entry : firstPerKind.entrySet()) {
                FixEngine.Applied result = new FixEngine().apply(files, entry.getValue().id());
                String code = result.files().stream()
                        .filter(file -> file.path().equals(path.getFileName().toString()))
                        .findFirst().orElseThrow().content();
                assertThat(compiles(className, code))
                        .as("исправление %s на %s должно компилироваться", entry.getKey(), path.getFileName())
                        .isTrue();
                applied++;
            }
        }

        assertThat(applied)
                .as("на проверенных файлах должно быть хотя бы несколько применимых исправлений")
                .isGreaterThanOrEqualTo(4);
    }

    private static boolean compiles(String className, String code) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Path output = Files.createTempDirectory("blueprints-corpus-fix");
            boolean compiled = compiler.getTask(new StringWriter(), files, diagnostics,
                    List.of("-proc:none", "-d", output.toString()), null, List.of(unit)).call();
            if (!compiled) {
                diagnostics.getDiagnostics().forEach(diagnostic -> System.out.println(className + ": javac " + diagnostic));
            }
            return compiled;
        } catch (Exception e) {
            throw new IllegalStateException("javac недоступен: " + e.getMessage(), e);
        }
    }
}
