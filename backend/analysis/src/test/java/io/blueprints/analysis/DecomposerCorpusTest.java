package io.blueprints.analysis;

import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Применить разбиение» на всём корпусе: что применилось — компилируется, а примеры с монолитом и static-группами
 * (02-library, 03-bank, 04-text-stats, 05-course-grades) действительно разбиваются, а не уходят в отказ quality gate.
 *
 * <p>Раньше: в 02/03 группа «Main» затирала исходный Main.java вместе с вложенными типами, в 03 вызовы перенесённых
 * методов из вложенных типов не квалифицировались, в 05 — вложенный тип в позиции выражения ({@code Mark.of(p)}).
 */
class DecomposerCorpusTest {

    private static final Path CORPUS = Path.of("..", "..", "corpus");
    private static final List<String> MUST_APPLY = List.of("02-library", "03-bank", "04-text-stats", "05-course-grades");

    @Test
    void appliedDecompositionCompilesOnEveryCorpusExample() throws Exception {
        if (!Files.isDirectory(CORPUS)) {
            return;
        }
        List<Path> examples;
        try (Stream<Path> list = Files.list(CORPUS)) {
            examples = list.filter(dir -> Files.isRegularFile(dir.resolve("Main.java"))).sorted().toList();
        }
        List<String> problems = new ArrayList<>();
        for (Path dir : examples) {
            String name = dir.getFileName().toString();
            List<SourceFile> files = List.of(new SourceFile("Main.java", Files.readString(dir.resolve("Main.java"), StandardCharsets.UTF_8)));
            Decomposer.Applied applied;
            try {
                applied = new Decomposer().apply(files);
            } catch (FixNotApplicableException refused) {
                if (MUST_APPLY.contains(name)) {
                    problems.add(name + ": отказ — " + refused.getMessage());
                }
                continue;
            }
            String errors = compileErrors(applied.files());
            if (!errors.isEmpty()) {
                problems.add(name + ": не компилируется после разбиения (" + applied.description() + "): " + errors);
            }
            assertThat(applied.files().stream().map(SourceFile::path).distinct().count())
                    .as("%s: имена файлов не повторяются", name)
                    .isEqualTo(applied.files().size());
        }
        assertThat(problems).as("разбиение на корпусе").isEmpty();
    }

    /** Ошибки javac по всем файлам вместе (первые пять), пусто — компилируется. */
    private static String compileErrors(List<SourceFile> files) throws Exception {
        Path source = Files.createTempDirectory("decompose-src");
        Path classes = Files.createTempDirectory("decompose-classes");
        List<String> paths = new ArrayList<>();
        for (SourceFile file : files) {
            Path path = source.resolve(file.path());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.content(), StandardCharsets.UTF_8);
            paths.add(path.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean ok = compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-d", classes.toString()),
                    null, manager.getJavaFileObjectsFromStrings(paths)).call();
            if (ok) {
                return "";
            }
            return diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .limit(5)
                    .map(diagnostic -> Path.of(diagnostic.getSource().getName()).getFileName() + ":"
                            + diagnostic.getLineNumber() + " " + diagnostic.getMessage(null))
                    .collect(Collectors.joining("; "));
        }
    }
}
