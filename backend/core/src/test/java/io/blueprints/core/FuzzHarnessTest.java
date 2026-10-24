package io.blueprints.core;

import io.blueprints.core.fuzz.FuzzHarness;
import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.graph.NodeKind;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import io.blueprints.core.ops.DefaultGraphOperations;
import io.blueprints.core.ops.GraphOp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FuzzHarnessTest {

    private static final String SOURCE = """
            import java.util.*;

            public class Main {
                static int counter;

                static int clamp(int x, int lo, int hi) {
                    if (x < lo) {
                        return lo;
                    }
                    return Math.min(x, hi);
                }

                private static String shout(String s, boolean twice) {
                    String up = s.toUpperCase();
                    return twice ? up + up : up;
                }

                static long sum(List<Integer> xs) {
                    long s = 0;
                    for (int x : xs) {
                        s += x;
                    }
                    return s;
                }

                static double avg(int[] a) {
                    return Arrays.stream(a).average().orElseThrow();
                }

                static void hang(char c) {
                    while (c != 'a') {
                        counter++;
                    }
                }

                static Map<String, Integer> index(Map<String, Integer> m) {
                    return m;
                }

                static <T> T first(List<T> xs) {
                    return xs.get(0);
                }

                static class Util {
                    static String join(String... parts) {
                        return String.join("-", parts);
                    }
                }

                public static void main(String[] args) {
                    System.out.println(clamp(5, 0, 3));
                }
            }
            """;

    @TempDir
    Path tmp;

    private static ProjectModel parse(String src) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", src)));
        assertThat(model.problems()).isEmpty();
        return model;
    }

    @Test
    void scanFindsSupportedStaticMethodsAndExplainsTheRest() {
        FuzzHarness.Scan scan = FuzzHarness.scan(parse(SOURCE));

        assertThat(scan.targets()).extracting(FuzzHarness.Target::key).containsExactly(
                "Main.clamp(int,int,int)", "Main.shout(String,boolean)", "Main.sum(List<Integer>)",
                "Main.avg(int[])", "Main.hang(char)", "Main$Util.join(String)");
        assertThat(scan.skipped()).extracting(FuzzHarness.Skipped::key)
                .containsExactly("Main.index(Map<String,Integer>)", "Main.first(List<T>)", "Main.main(String[])");
        assertThat(scan.skipped().get(0).reason()).contains("не поддержан");
    }

    @Test
    void harnessIsDeterministicAndSurvivesExceptionsAndHangs() throws Exception {
        ProjectModel model = parse(SOURCE);
        FuzzHarness.Harness harness = FuzzHarness.generate(FuzzHarness.scan(model).targets(), 8);

        String first = run(model, harness);
        String second = run(model, harness);

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("clamp(0, 0, 0) -> 0", "shout(\"Привет\", true) -> \"ПРИВЕТПРИВЕТ\"",
                "sum([]) -> 0", "avg([]) -> throws java.util.NoSuchElementException: No value present",
                "hang('a') -> ok", "hang('Z') -> timeout", "join([]) -> \"\"");
        assertThat(first.lines().filter(l -> l.contains(" -> ")).count()).isEqualTo(6 * 8);
    }

    @Test
    void refactoringKeepsFuzzOutputAndBehaviourChangeShowsUp() throws Exception {
        ProjectModel baseline = parse(SOURCE);
        DefaultGraphBuilder graphs = new DefaultGraphBuilder();
        DefaultGraphOperations ops = new DefaultGraphOperations();

        // Рефакторинг через граф: вынести цикл суммы в отдельный метод — поведение то же.
        ProjectModel refactored = parse(SOURCE);
        String sumId = method(graphs, refactored, "sum");
        String loop = block(graphs, refactored, sumId, NodeKind.LOOP);
        ops.apply(refactored, new GraphOp("extractMethod", null, List.of(loop), null, null, null, "addAll"));

        // Изменение поведения: граница clamp сдвинута.
        ProjectModel changed = parse(SOURCE);
        String clampId = method(graphs, changed, "clamp");
        String cond = block(graphs, changed, clampId, NodeKind.CONDITION);
        ops.apply(changed, new GraphOp("editNode", cond, null, "x > lo", null, null, null));

        List<FuzzHarness.Target> common = FuzzHarness.common(baseline, refactored);
        assertThat(common).hasSize(6);
        common = common.stream().filter(t -> !t.name().equals("hang")).toList(); // таймауты проверены выше
        FuzzHarness.Harness harness = FuzzHarness.generate(common, 10);
        String expected = run(baseline, harness);

        assertThat(run(refactored, harness)).isEqualTo(expected);
        String actual = run(changed, harness);
        assertThat(actual).isNotEqualTo(expected);
        List<String> diff = new ArrayList<>(actual.lines().toList());
        diff.removeAll(expected.lines().toList());
        assertThat(diff).isNotEmpty().allMatch(l -> l.startsWith("clamp("));
    }

    @Test
    void commonSkipsMethodsWhoseSignatureChanged() {
        ProjectModel renamed = parse(SOURCE.replace("static long sum(", "static long total("));

        assertThat(FuzzHarness.common(parse(SOURCE), renamed)).extracting(FuzzHarness.Target::name)
                .doesNotContain("sum").contains("clamp");
    }

    // ------------------------------------------------------------ helpers

    private static String method(DefaultGraphBuilder graphs, ProjectModel model, String name) {
        return graphs.overview(model).nodes().stream().filter(n -> n.label().equals(name)).findFirst().orElseThrow().id();
    }

    private static String block(DefaultGraphBuilder graphs, ProjectModel model, String methodId, NodeKind kind) {
        return graphs.methodFlow(model, methodId).nodes().stream().filter(n -> n.kind() == kind)
                .map(GraphNode::id).findFirst().orElseThrow();
    }

    /** Скомпилировать проект + обвязку javac и запустить обвязку (как сделает runner). */
    private String run(ProjectModel model, FuzzHarness.Harness harness) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory(tmp, "run");
        List<String> sources = new ArrayList<>();
        List<SourceFile> files = new ArrayList<>(new CodeGenerator().print(model));
        files.add(harness.file());
        for (SourceFile f : files) {
            Path p = dir.resolve(f.path());
            Files.writeString(p, f.content(), StandardCharsets.UTF_8);
            sources.add(p.toString());
        }
        List<String> args = new ArrayList<>(List.of("-d", dir.toString(), "-encoding", "UTF-8", "-proc:none"));
        args.addAll(sources);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .as("компиляция").isZero();
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dstdout.encoding=UTF-8", "-cp", dir.toString(), harness.mainClass())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        return out;
    }
}
