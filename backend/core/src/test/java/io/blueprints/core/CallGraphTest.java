package io.blueprints.core;

import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.EdgeKind;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphEdge;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CallGraphTest {

    private static final String SOURCE = """
            import java.util.*;
            import java.util.function.*;

            public class Main {
                static final Parser PARSER = new Parser();
                private final List<Integer> items = new ArrayList<>();
                private final Analyzer analyzer;

                Main() {
                    this(new Analyzer());
                }

                Main(Analyzer analyzer) {
                    this.analyzer = analyzer;
                }

                void add(int x) {
                    items.add(x);          // List.add — не наш add
                    log("add");
                    log("add", 2);
                }

                void log(String s) {
                    System.out.println(s);
                }

                void log(String s, int times) {
                    for (int i = 0; i < times; i++) {
                        log(s);
                    }
                }

                int run(String text) {
                    List<Integer> xs = PARSER.parse(text);
                    Function<Integer, Integer> f = v -> analyzer.score(v);
                    xs.forEach(this::add);
                    return f.apply(Optimizer.best(xs)) + fact(3);
                }

                static int fact(int n) {
                    return n <= 1 ? 1 : n * fact(n - 1);
                }

                public static void main(String[] args) {
                    Main m = new Main();
                    System.out.println(m.run(args.length > 0 ? args[0] : "1 2 3"));
                }

                static class Parser {
                    List<Integer> parse(String text) {
                        List<Integer> out = new ArrayList<>();
                        for (String t : text.split(" ")) {
                            out.add(Integer.parseInt(t));
                        }
                        return out;
                    }
                }
            }

            class Analyzer {
                int score(int v) {
                    return v * 2;
                }
            }

            class Optimizer {
                static int best(List<Integer> xs) {
                    return Collections.max(xs);
                }
            }
            """;

    private final DefaultGraphBuilder builder = new DefaultGraphBuilder();

    private static ProjectModel parse(String src) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", src)));
        assertThat(model.problems()).isEmpty();
        return model;
    }

    /** Рёбра call как «откуда → куда [метка]» по подписям узлов (у перегрузок — с числом параметров). */
    private List<String> calls(String src) {
        Graph g = builder.overview(parse(src));
        Map<String, String> names = g.nodes().stream().collect(Collectors.toMap(GraphNode::id, CallGraphTest::name));
        return g.edges().stream().filter(e -> e.kind() == EdgeKind.CALL)
                .map(e -> names.get(e.from()) + " -> " + names.get(e.to()) + (e.label() == null ? "" : " " + e.label()))
                .toList();
    }

    private static String name(GraphNode n) {
        if (n.code() != null && n.code().contains("(")) {
            String params = n.code().substring(n.code().indexOf('(') + 1, n.code().lastIndexOf(')')).strip();
            int count = params.isEmpty() ? 0 : params.split(",").length;
            return n.label() + "/" + count;
        }
        return n.label();
    }

    @Test
    void resolvesProjectCallsAndIgnoresLibraries() {
        List<String> calls = calls(SOURCE);

        assertThat(calls).contains(
                "PARSER/0 -> Parser",                 // поле → new Parser(): явного конструктора нет — ребро на класс
                "Main/0 -> Main/1",                   // this(...)
                "Main/0 -> Analyzer",
                "add/1 -> log/1",
                "add/1 -> log/2",
                "log/2 -> log/1",
                "run/1 -> parse/1",                   // через статическое поле типа Parser
                "run/1 -> score/1",                   // внутри лямбды — ребро от run
                "run/1 -> best/1",
                "run/1 -> fact/1",
                "fact/1 -> fact/1",                   // рекурсия
                "main/1 -> Main/0",
                "main/1 -> run/1");
        // items.add(x) — это List.add, а не Main.add; println, parseInt, Collections.max — библиотеки.
        assertThat(calls).doesNotContain("add/1 -> add/1");
        assertThat(calls).allSatisfy(c -> assertThat(c).doesNotContain("println", "parseInt", "max"));
    }

    @Test
    void repeatedCallsMergeIntoOneEdgeWithCount() {
        List<String> calls = calls("""
                public class T {
                    void a() {
                        b();
                        b();
                        if (true) {
                            b();
                        }
                    }
                    void b() {
                    }
                }
                """);

        assertThat(calls).containsExactly("a/0 -> b/0 ×3");
    }

    @Test
    void fallsBackToNamesWhenSymbolSolverFails() {
        // Тип аргумента из неизвестной библиотеки: SymbolSolver не выведет перегрузку, а по имени и числу
        // аргументов метод проекта однозначен.
        List<String> calls = calls("""
                import com.unknown.Lib;

                public class T {
                    Helper helper = new Helper();
                    void a(Lib lib) {
                        helper.run(lib.value());
                        Helper.stat(lib.value(), 1);
                        lib.run(1);                 // метод чужой библиотеки — ребра нет
                    }
                }
                class Helper {
                    void run(Object o) {
                    }
                    static void stat(Object o, int k) {
                    }
                }
                """);

        assertThat(calls).contains("a/1 -> run/1", "a/1 -> stat/2");
        assertThat(calls).doesNotContain("a/1 -> run/1 ×2");
    }

    @Test
    void edgesPointToExistingNodes() {
        Graph g = builder.overview(parse(SOURCE));
        List<String> ids = g.nodes().stream().map(GraphNode::id).toList();

        assertThat(g.edges()).extracting(GraphEdge::from).allMatch(ids::contains);
        assertThat(g.edges()).extracting(GraphEdge::to).allMatch(ids::contains);
        assertThat(g.edges()).extracting(GraphEdge::id).doesNotHaveDuplicates();
    }
}
