package io.blueprints.core;

import io.blueprints.core.fuzz.FuzzHarness;
import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphEdge;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.graph.NodeKind;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import io.blueprints.core.ops.DefaultGraphOperations;
import io.blueprints.core.ops.GraphOp;
import io.blueprints.core.ops.UnsupportedGraphOpException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Каждый пример {@code corpus/} и пример scope ({@code src/test/resources/scope}): разбор без ошибок, печать
 * байт-в-байт, обзорный граф и блок-схема каждого метода строятся и согласованы. Новый пример в корпусе
 * проверяется автоматически. Конструкции, ставшие {@code raw}, печатаются в лог — это материал для docs/scope.md.
 */
class CorpusRoundTripTest {

    private final DefaultGraphBuilder builder = new DefaultGraphBuilder();

    @TestFactory
    Stream<DynamicTest> everyExampleRoundTrips() throws IOException, URISyntaxException {
        List<Path> projects = new ArrayList<>();
        Path corpus = findCorpus();
        if (corpus != null) {
            try (Stream<Path> dirs = Files.list(corpus)) {
                dirs.filter(Files::isDirectory).sorted().forEach(projects::add);
            }
        }
        projects.add(Path.of(getClass().getResource("/scope").toURI()));
        assertThat(projects).as("корпус и пример scope").hasSizeGreaterThanOrEqualTo(2);
        return projects.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> check(dir)));
    }

    private void check(Path dir) throws IOException {
        List<SourceFile> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted()
                    .map(p -> new SourceFile(dir.relativize(p).toString(), read(p))).toList();
        }
        assertThat(files).as("в примере есть .java").isNotEmpty();

        ProjectModel model = new JavaProjectParser().parse(files);
        assertThat(model.problems()).as("разбор без ошибок").isEmpty();
        assertThat(new CodeGenerator().print(model)).as("печать байт-в-байт").containsExactlyElementsOf(files);

        Graph overview = builder.overview(model);
        assertConsistent(overview);
        Map<String, Integer> raw = new TreeMap<>();
        int methods = 0;
        for (GraphNode n : overview.nodes()) {
            if (n.kind() != NodeKind.METHOD && n.kind() != NodeKind.CONSTRUCTOR) {
                continue;
            }
            methods++;
            Graph flow = builder.methodFlow(model, n.id());
            assertConsistent(flow);
            assertThat(flow.nodes()).extracting(GraphNode::id).contains(n.id() + ":start", n.id() + ":end");
            flow.nodes().stream().filter(b -> b.kind() == NodeKind.RAW)
                    .forEach(b -> raw.merge(firstWord(b.code()), 1, Integer::sum));
            checkInsertAtStart(files, n);
        }
        FuzzHarness.Scan fuzz = FuzzHarness.scan(model);
        assertHarnessCompiles(files, FuzzHarness.generate(fuzz.targets(), 5));
        System.out.printf("[corpus] %s: %d файлов, %d узлов обзора, %d методов, raw: %s, фаззинг: %d методов (пропущено %d)%n",
                dir.getFileName(), files.size(), overview.nodes().size(), methods, raw.isEmpty() ? "нет" : raw,
                fuzz.targets().size(), fuzz.skipped().size());
    }

    /** Обвязка фаззинга компилируется вместе с примером (запуск и сравнение — FuzzHarnessTest и /verify). */
    private static void assertHarnessCompiles(List<SourceFile> files, FuzzHarness.Harness harness) throws IOException {
        Path dir = Files.createTempDirectory("fuzz-compile");
        List<String> args = new ArrayList<>(List.of("-d", dir.toString(), "-encoding", "UTF-8", "-proc:none"));
        List<SourceFile> all = new ArrayList<>(files);
        all.add(harness.file());
        for (SourceFile f : all) {
            Path p = dir.resolve("src").resolve(f.path());
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.content(), StandardCharsets.UTF_8);
            args.add(p.toString());
        }
        StringWriter err = new StringWriter();
        boolean ok = ToolProvider.getSystemJavaCompiler()
                .getTask(err, null, null, args.subList(0, 5), null,
                        ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8)
                                .getJavaFileObjectsFromStrings(args.subList(5, args.size())))
                .call();
        assertThat(ok).as("обвязка фаззинга компилируется:\n" + err).isTrue();
    }

    /**
     * Операция графа на каждом методе: вставка пустого оператора в начало тела проходит (у методов без тела —
     * понятный отказ), остальной код печатается как был.
     */
    private void checkInsertAtStart(List<SourceFile> files, GraphNode method) {
        ProjectModel fresh = new JavaProjectParser().parse(files);
        String file = method.range().file();
        String before = files.stream().filter(f -> f.path().equals(file)).findFirst().orElseThrow().content();
        GraphOp op = new GraphOp("insertAfter", method.id() + ":start", null, ";", null, null, null);
        try {
            new DefaultGraphOperations().apply(fresh, op);
        } catch (UnsupportedGraphOpException e) {
            assertThat(method.code()).as("отказ только у методов без тела: " + e.getMessage()).doesNotContain("{");
            assertThat(e.getMessage()).contains("нет тела");
            return;
        }
        String after = new CodeGenerator().print(fresh).stream().filter(f -> f.path().equals(file))
                .findFirst().orElseThrow().content();
        assertThat(after.length()).as(method.label()).isGreaterThan(before.length());
        assertThat(new JavaProjectParser().parse(List.of(new SourceFile(file, after))).problems()).isEmpty();
    }

    /** Уникальные id, рёбра и parent ссылаются на существующие узлы, диапазоны в пределах файла. */
    private static void assertConsistent(Graph g) {
        Set<String> ids = new HashSet<>();
        for (GraphNode n : g.nodes()) {
            assertThat(ids.add(n.id())).as("уникальный id " + n.id()).isTrue();
        }
        for (GraphNode n : g.nodes()) {
            if (n.parent() != null) {
                assertThat(ids).as("parent узла " + n.id()).contains(n.parent());
            }
            if (n.range() != null) {
                assertThat(n.range().startLine()).isPositive().isLessThanOrEqualTo(n.range().endLine());
            }
        }
        Set<String> edgeIds = new HashSet<>();
        for (GraphEdge e : g.edges()) {
            assertThat(edgeIds.add(e.id())).as("уникальный id ребра " + e.id()).isTrue();
            assertThat(ids).as("ребро " + e.id()).contains(e.from(), e.to());
        }
    }

    private static String firstWord(String code) {
        String c = code == null ? "" : code.strip();
        int end = 0;
        while (end < c.length() && Character.isJavaIdentifierPart(c.charAt(end))) {
            end++;
        }
        return end == 0 ? c.substring(0, Math.min(10, c.length())) : c.substring(0, end);
    }

    /** {@code corpus/} в корне репозитория: тесты запускаются из backend/core (или из корня). */
    private static Path findCorpus() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            Path c = p.resolve("corpus");
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        return null;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
