package io.blueprints.core;

import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.EdgeKind;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphEdge;
import io.blueprints.core.graph.GraphLevel;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.graph.NodeKind;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.InstanceOfAssertFactories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MethodFlowTest {

    private final DefaultGraphBuilder builder = new DefaultGraphBuilder();

    // ------------------------------------------------------------ helpers

    private static ProjectModel parse(String body) {
        String src = "import java.util.*;\nimport java.io.*;\n\npublic class T {\n" + body + "\n}\n";
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("T.java", src)));
        assertThat(model.problems()).as("исходник теста должен разбираться").isEmpty();
        return model;
    }

    private Graph flow(String body, String method) {
        ProjectModel model = parse(body);
        Graph overview = builder.overview(model);
        String id = overview.nodes().stream()
                .filter(n -> (n.kind() == NodeKind.METHOD || n.kind() == NodeKind.CONSTRUCTOR)
                        && n.label().equals(method))
                .findFirst().orElseThrow().id();
        Graph g = builder.methodFlow(model, id);
        assertWellFormed(g, id);
        return g;
    }

    /** Инварианты, на которые опирается фронт: уникальные id, рёбра указывают на существующие узлы, parent существует. */
    private static void assertWellFormed(Graph g, String methodId) {
        assertThat(g.level()).isEqualTo(GraphLevel.METHOD);
        assertThat(g.scope()).isEqualTo(methodId);
        Set<String> ids = new HashSet<>();
        for (GraphNode n : g.nodes()) {
            assertThat(ids.add(n.id())).as("дубликат id " + n.id()).isTrue();
        }
        assertThat(ids).contains(methodId + ":start", methodId + ":end");
        for (GraphEdge e : g.edges()) {
            assertThat(ids).as("ребро " + e).contains(e.from(), e.to());
        }
        assertThat(g.edges().stream().map(GraphEdge::id).distinct().count()).isEqualTo(g.edges().size());
        for (GraphNode n : g.nodes()) {
            if (n.parent() != null) {
                assertThat(ids).as("parent узла " + n.id()).contains(n.parent());
            }
            if (n.astId() != null) {
                assertThat(n.id()).isEqualTo("g" + n.astId());
            }
        }
    }

    private static GraphNode node(Graph g, NodeKind kind, String codeFragment) {
        return g.nodes().stream()
                .filter(n -> n.kind() == kind && n.code() != null && n.code().contains(codeFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет узла " + kind + " с кодом «" + codeFragment + "»: "
                        + g.nodes().stream().map(n -> n.kind() + ":" + n.code()).toList()));
    }

    private static GraphNode start(Graph g) {
        return g.nodes().stream().filter(n -> n.kind() == NodeKind.START).findFirst().orElseThrow();
    }

    private static GraphNode end(Graph g) {
        return g.nodes().stream().filter(n -> n.kind() == NodeKind.END).findFirst().orElseThrow();
    }

    private static boolean hasEdge(Graph g, GraphNode from, GraphNode to, EdgeKind kind) {
        return g.edges().stream().anyMatch(e -> e.from().equals(from.id()) && e.to().equals(to.id()) && e.kind() == kind);
    }

    private static GraphEdge edge(Graph g, GraphNode from, GraphNode to) {
        return g.edges().stream().filter(e -> e.from().equals(from.id()) && e.to().equals(to.id()))
                .findFirst().orElseThrow(() -> new AssertionError("нет ребра " + from.code() + " → " + to.code()));
    }

    // ------------------------------------------------------------ tests

    @Test
    void sequenceOfSimpleStatements() {
        Graph g = flow("""
                void f(List<Integer> xs) {
                    int a = 1;
                    a += 2;
                    xs.add(a);
                    new StringBuilder();
                }""", "f");

        GraphNode v = node(g, NodeKind.VARIABLE, "int a = 1");
        GraphNode op = node(g, NodeKind.OPERATION, "a += 2");
        GraphNode call = node(g, NodeKind.CALL, "xs.add(a)");
        GraphNode ctor = node(g, NodeKind.CALL, "new StringBuilder()");
        assertThat(hasEdge(g, start(g), v, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, v, op, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, op, call, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, call, ctor, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, ctor, end(g), EdgeKind.NEXT)).isTrue();
        assertThat(start(g).code()).contains("void f(List<Integer> xs)");
        assertThat(v.range().startLine()).isEqualTo(6);
        assertThat(v.range().file()).isEqualTo("T.java");
    }

    @Test
    void ifElseHasYesNoBranchesThatJoin() {
        Graph g = flow("""
                int f(int x) {
                    int r;
                    if (x > 0) {
                        r = 1;
                    } else {
                        r = 2;
                    }
                    return r;
                }""", "f");

        GraphNode cond = node(g, NodeKind.CONDITION, "x > 0");
        GraphNode yes = node(g, NodeKind.OPERATION, "r = 1");
        GraphNode no = node(g, NodeKind.OPERATION, "r = 2");
        GraphNode ret = node(g, NodeKind.RETURN, "return r");
        assertThat(edge(g, cond, yes).kind()).isEqualTo(EdgeKind.TRUE);
        assertThat(edge(g, cond, yes).label()).isEqualTo("Да");
        assertThat(edge(g, cond, no).kind()).isEqualTo(EdgeKind.FALSE);
        assertThat(edge(g, cond, no).label()).isEqualTo("Нет");
        assertThat(hasEdge(g, yes, ret, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, no, ret, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, ret, end(g), EdgeKind.NEXT)).isTrue();
        assertThat(yes.parent()).isEqualTo(cond.id());
        assertThat(cond.range().startLine()).as("подсветка — строка условия").isEqualTo(cond.range().endLine());
    }

    @Test
    void ifWithoutElseFallsThroughOnNo() {
        Graph g = flow("""
                void f(int x) {
                    if (x > 0) return;
                    System.out.println(x);
                }""", "f");

        GraphNode cond = node(g, NodeKind.CONDITION, "x > 0");
        GraphNode ret = node(g, NodeKind.RETURN, "return");
        GraphNode print = node(g, NodeKind.CALL, "println");
        assertThat(hasEdge(g, cond, ret, EdgeKind.TRUE)).isTrue();
        assertThat(hasEdge(g, cond, print, EdgeKind.FALSE)).isTrue();
        assertThat(hasEdge(g, ret, end(g), EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, ret, print, EdgeKind.NEXT)).as("после return потока нет").isFalse();
    }

    @Test
    void elseIfChain() {
        Graph g = flow("""
                String f(int x) {
                    if (x < 0) return "neg";
                    else if (x == 0) return "zero";
                    else return "pos";
                }""", "f");

        GraphNode c1 = node(g, NodeKind.CONDITION, "x < 0");
        GraphNode c2 = node(g, NodeKind.CONDITION, "x == 0");
        assertThat(hasEdge(g, c1, c2, EdgeKind.FALSE)).isTrue();
        assertThat(g.nodes().stream().filter(n -> n.kind() == NodeKind.RETURN)).hasSize(3);
        assertThat(g.edges().stream().filter(e -> e.to().equals(end(g).id()))).hasSize(3);
    }

    @Test
    void forEachLoopMatchesFrontendMock() {
        // Тот же метод, что в frontend/src/mocks (corpus/00-hello).
        Graph g = flow("""
                static List<Integer> processOrders(List<Integer> orders) {
                    List<Integer> result = new ArrayList<>();
                    if (orders == null || orders.isEmpty()) {
                        return result;
                    }
                    for (int order : orders) {
                        if (!result.contains(order)) {
                            result.add(order * 2);
                        }
                    }
                    return result;
                }""", "processOrders");

        GraphNode loop = node(g, NodeKind.LOOP, "for (int order : orders)");
        GraphNode inner = node(g, NodeKind.CONDITION, "!result.contains(order)");
        GraphNode add = node(g, NodeKind.CALL, "result.add(order * 2)");
        GraphNode last = g.nodes().stream().filter(n -> n.kind() == NodeKind.RETURN).reduce((a, b) -> b).orElseThrow();
        assertThat(loop.label()).isEqualTo("Цикл (for each)");
        assertThat(loop.meta()).containsEntry("loopKind", "for-each");
        assertThat(hasEdge(g, node(g, NodeKind.CONDITION, "orders == null"), loop, EdgeKind.FALSE)).isTrue();
        assertThat(hasEdge(g, loop, inner, EdgeKind.LOOP_BODY)).isTrue();
        assertThat(hasEdge(g, inner, add, EdgeKind.TRUE)).isTrue();
        assertThat(hasEdge(g, add, loop, EdgeKind.LOOP_BACK)).isTrue();
        assertThat(hasEdge(g, inner, loop, EdgeKind.FALSE)).isTrue();
        assertThat(hasEdge(g, loop, last, EdgeKind.LOOP_EXIT)).isTrue();
        assertThat(inner.parent()).isEqualTo(loop.id());
        assertThat(add.parent()).isEqualTo(inner.id());
        assertThat(loop.range().startLine()).as("рамка цикла — весь цикл").isLessThan(loop.range().endLine());
    }

    @Test
    void forWhileAndDoWhileLoops() {
        Graph g = flow("""
                int f(int n) {
                    int s = 0;
                    for (int i = 0; i < n; i++) s += i;
                    while (s > 100) s /= 2;
                    do { s--; } while (s > 50);
                    return s;
                }""", "f");

        GraphNode forLoop = node(g, NodeKind.LOOP, "for (int i = 0; i < n; i++)");
        GraphNode whileLoop = node(g, NodeKind.LOOP, "while (s > 100)");
        GraphNode doLoop = node(g, NodeKind.LOOP, "do … while (s > 50)");
        assertThat(hasEdge(g, forLoop, whileLoop, EdgeKind.LOOP_EXIT)).isTrue();
        assertThat(hasEdge(g, whileLoop, doLoop, EdgeKind.LOOP_EXIT)).isTrue();
        assertThat(hasEdge(g, node(g, NodeKind.OPERATION, "s--"), doLoop, EdgeKind.LOOP_BACK)).isTrue();
        assertThat(hasEdge(g, doLoop, node(g, NodeKind.RETURN, "return s"), EdgeKind.LOOP_EXIT)).isTrue();
    }

    @Test
    void infiniteLoopExitsOnlyViaBreak() {
        Graph g = flow("""
                int f(int d) {
                    for (;;) {
                        if (d > 10) break;
                        d++;
                    }
                    while (true) {
                        if (d < 0) throw new IllegalStateException("x");
                        d--;
                    }
                }""", "f");

        GraphNode forLoop = node(g, NodeKind.LOOP, "for (;;)");
        GraphNode whileLoop = node(g, NodeKind.LOOP, "while (true)");
        assertThat(forLoop.meta()).containsEntry("infinite", true);
        assertThat(g.edges()).noneMatch(e -> e.kind() == EdgeKind.LOOP_EXIT);
        GraphNode brk = g.nodes().stream().filter(n -> n.kind() == NodeKind.BREAK).findFirst().orElseThrow();
        assertThat(hasEdge(g, brk, whileLoop, EdgeKind.BREAK)).isTrue();
        GraphNode thr = node(g, NodeKind.THROW, "IllegalStateException");
        assertThat(edge(g, thr, end(g)).label()).isEqualTo("IllegalStateException");
    }

    @Test
    void breakAndContinueIncludingLabels() {
        Graph g = flow("""
                int f(int[][] m) {
                    int found = -1;
                    outer:
                    for (int[] row : m) {
                        for (int v : row) {
                            if (v < 0) continue;
                            if (v == 0) continue outer;
                            if (v > 9) break outer;
                            if (v == 5) break;
                            found = v;
                        }
                    }
                    return found;
                }""", "f");

        GraphNode outer = node(g, NodeKind.LOOP, "int[] row");
        GraphNode inner = node(g, NodeKind.LOOP, "int v : row");
        GraphNode ret = node(g, NodeKind.RETURN, "return found");
        List<GraphNode> continues = g.nodes().stream().filter(n -> n.kind() == NodeKind.CONTINUE).toList();
        List<GraphNode> breaks = g.nodes().stream().filter(n -> n.kind() == NodeKind.BREAK).toList();
        assertThat(outer.meta()).containsEntry("jumpLabel", "outer");
        assertThat(hasEdge(g, continues.get(0), inner, EdgeKind.CONTINUE)).isTrue();
        assertThat(hasEdge(g, continues.get(1), outer, EdgeKind.CONTINUE)).isTrue();
        assertThat(hasEdge(g, breaks.get(0), ret, EdgeKind.BREAK)).as("break outer выходит из внешнего цикла").isTrue();
        assertThat(hasEdge(g, breaks.get(1), outer, EdgeKind.LOOP_BACK) || hasEdge(g, breaks.get(1), outer, EdgeKind.BREAK))
                .as("break выходит из внутреннего цикла и продолжает внешний").isTrue();
    }

    @Test
    void classicSwitchWithFallthroughAndDefault() {
        Graph g = flow("""
                String f(int d) {
                    String r = "";
                    switch (d) {
                        case 1:
                        case 2:
                            r = "low";
                            break;
                        case 3:
                            r = "mid";
                        default:
                            r += "!";
                    }
                    return r;
                }""", "f");

        GraphNode sw = node(g, NodeKind.SWITCH, "d");
        List<GraphNode> cases = g.nodes().stream().filter(n -> n.kind() == NodeKind.CASE).toList();
        assertThat(cases).extracting(GraphNode::code).containsExactly("case 1", "case 2", "case 3", "default");
        assertThat(cases).allMatch(c -> sw.id().equals(c.parent()));
        assertThat(edge(g, sw, cases.get(2)).kind()).isEqualTo(EdgeKind.CASE);
        assertThat(edge(g, sw, cases.get(2)).label()).isEqualTo("3");
        assertThat(hasEdge(g, cases.get(0), cases.get(1), EdgeKind.NEXT)).as("пустой case 1 проваливается в case 2").isTrue();
        GraphNode mid = node(g, NodeKind.OPERATION, "r = \"mid\"");
        assertThat(hasEdge(g, mid, cases.get(3), EdgeKind.NEXT)).as("case 3 без break проваливается в default").isTrue();
        GraphNode ret = node(g, NodeKind.RETURN, "return r");
        GraphNode brk = g.nodes().stream().filter(n -> n.kind() == NodeKind.BREAK).findFirst().orElseThrow();
        assertThat(hasEdge(g, brk, ret, EdgeKind.BREAK)).isTrue();
        assertThat(hasEdge(g, node(g, NodeKind.OPERATION, "r += \"!\""), ret, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, sw, ret, EdgeKind.NEXT)).as("есть default — обхода switch нет").isFalse();
    }

    @Test
    void arrowSwitchWithoutDefaultHasNoMatchExit() {
        Graph g = flow("""
                void f(int d) {
                    switch (d) {
                        case 1 -> System.out.println("one");
                        case 2, 3 -> { System.out.println("few"); }
                    }
                    System.out.println("done");
                }""", "f");

        GraphNode sw = node(g, NodeKind.SWITCH, "d");
        GraphNode done = node(g, NodeKind.CALL, "\"done\"");
        assertThat(hasEdge(g, node(g, NodeKind.CALL, "\"one\""), done, EdgeKind.NEXT)).isTrue();
        assertThat(hasEdge(g, node(g, NodeKind.CALL, "\"few\""), done, EdgeKind.NEXT)).isTrue();
        assertThat(edge(g, sw, done).label()).isEqualTo("нет совпадений");
        assertThat(g.nodes().stream().filter(n -> n.kind() == NodeKind.CASE).map(GraphNode::code))
                .containsExactly("case 1", "case 2, 3");
    }

    @Test
    void tryCatchFinallyAndThrow() {
        Graph g = flow("""
                int f(String s) throws IOException {
                    int v = 0;
                    try (Reader r = new StringReader(s)) {
                        v = Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        throw new IOException("bad");
                    } catch (IllegalStateException | IllegalArgumentException e) {
                        v = -1;
                    } finally {
                        System.out.println("finally");
                    }
                    return v;
                }""", "f");

        GraphNode tr = g.nodes().stream().filter(n -> n.kind() == NodeKind.TRY).findFirst().orElseThrow();
        assertThat(tr.code()).startsWith("try (Reader r = new StringReader(s))");
        GraphNode parse = node(g, NodeKind.OPERATION, "Integer.parseInt");
        assertThat(parse.parent()).isEqualTo(tr.id());
        List<GraphNode> catches = g.nodes().stream().filter(n -> n.kind() == NodeKind.CATCH).toList();
        assertThat(catches).hasSize(2);
        assertThat(catches).allMatch(c -> tr.id().equals(c.parent()));
        assertThat(edge(g, tr, catches.get(0)).kind()).isEqualTo(EdgeKind.EXCEPTION);
        assertThat(edge(g, tr, catches.get(0)).label()).isEqualTo("NumberFormatException");
        GraphNode thr = node(g, NodeKind.THROW, "throw new IOException");
        assertThat(thr.parent()).isEqualTo(catches.get(0).id());
        assertThat(edge(g, thr, end(g)).kind()).isEqualTo(EdgeKind.EXCEPTION);
        GraphNode fin = g.nodes().stream().filter(n -> n.kind() == NodeKind.FINALLY).findFirst().orElseThrow();
        assertThat(hasEdge(g, parse, fin, EdgeKind.FINALLY)).isTrue();
        assertThat(hasEdge(g, node(g, NodeKind.OPERATION, "v = -1"), fin, EdgeKind.FINALLY)).isTrue();
        assertThat(fin.parent()).isEqualTo(tr.id());
        GraphNode print = node(g, NodeKind.CALL, "\"finally\"");
        assertThat(print.parent()).isEqualTo(fin.id());
        assertThat(hasEdge(g, print, node(g, NodeKind.RETURN, "return v"), EdgeKind.NEXT)).isTrue();
    }

    @Test
    void unsupportedConstructsBecomeRawNodes() {
        Graph g = flow("""
                void f(Object lock) {
                    class Local { int x; }
                    synchronized (lock) {
                        System.out.println(new Local().x);
                    }
                    Runnable r = () -> { System.out.println("lambda"); };
                    r.run();
                }""", "f");

        List<GraphNode> raws = g.nodes().stream().filter(n -> n.kind() == NodeKind.RAW).toList();
        assertThat(raws).hasSize(2);
        assertThat(raws.get(1).code()).contains("synchronized (lock)").contains("println");
        assertThat(node(g, NodeKind.VARIABLE, "Runnable r").code()).contains("lambda");
        assertThat(hasEdge(g, raws.get(1), node(g, NodeKind.VARIABLE, "Runnable r"), EdgeKind.NEXT)).isTrue();
    }

    @Test
    void constructorAndAbstractMethod() {
        Graph ctor = flow("""
                int x;
                T(int x) {
                    this();
                    this.x = x;
                }
                T() { }""", "T");
        assertThat(start(ctor).label()).isEqualTo("Конструктор");
        assertThat(node(ctor, NodeKind.CALL, "this()").label()).isEqualTo("Вызов this(...)");

        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("A.java",
                "abstract class A { abstract void g(); }")));
        String id = builder.overview(model).nodes().stream().filter(n -> n.label().equals("g")).findFirst().orElseThrow().id();
        Graph g = builder.methodFlow(model, id);
        assertThat(g.nodes()).hasSize(2);
        assertThat(g.edges()).singleElement().satisfies(e -> {
            assertThat(e.from()).isEqualTo(id + ":start");
            assertThat(e.to()).isEqualTo(id + ":end");
        });
    }

    @Test
    void coversLinkAnalyzerFindingsToBlocks() {
        ProjectModel model = parse("""
                void f(List<Integer> xs, List<Integer> ys) {
                    for (int x : xs) {
                        if (ys.contains(x)) { System.out.println(x); }
                    }
                }""");
        String methodId = builder.overview(model).nodes().stream().filter(n -> n.label().equals("f"))
                .findFirst().orElseThrow().id();
        Graph g = builder.methodFlow(model, methodId);

        // Анализатор ссылается на MethodCallExpr ys.contains(x) как "g" + astId.
        var call = model.units().get("T.java").findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("contains")).findFirst().orElseThrow();
        String findingNodeId = "g" + model.idOf(call);
        GraphNode cond = node(g, NodeKind.CONDITION, "ys.contains(x)");
        assertThat(cond.meta().get("covers")).asInstanceOf(InstanceOfAssertFactories.LIST).contains(findingNodeId);
        GraphNode print = node(g, NodeKind.CALL, "println");
        assertThat(print.meta().get("covers")).asInstanceOf(InstanceOfAssertFactories.LIST).doesNotContain(findingNodeId);
        assertThat(start(g).meta().get("covers")).asInstanceOf(InstanceOfAssertFactories.LIST).containsExactly(methodId);
    }

    @Test
    void commentsAreNotPartOfBlockCode() {
        Graph g = flow("""
                void f() {
                    // считаем
                    int a = 1; // хвост
                }""", "f");
        assertThat(node(g, NodeKind.VARIABLE, "int a").code()).isEqualTo("int a = 1");
    }

    @Test
    void rejectsUnknownOrNonMethodIds() {
        ProjectModel model = parse("int x;\nvoid f() {}");
        String fieldId = builder.overview(model).nodes().stream().filter(n -> n.kind() == NodeKind.FIELD)
                .findFirst().orElseThrow().id();
        assertThatThrownBy(() -> builder.methodFlow(model, "g999999")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.methodFlow(model, fieldId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.methodFlow(model, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyMethodOfTheCorpusBuildsWithoutRawFallbacksForKnownStatements() throws IOException {
        Path corpus = Path.of("..", "..", "corpus");
        if (!Files.isDirectory(corpus)) {
            return;
        }
        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(corpus)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(javaFiles).isNotEmpty();
        for (Path p : javaFiles) {
            ProjectModel model = new JavaProjectParser().parse(List.of(
                    new SourceFile(p.getFileName().toString(), Files.readString(p))));
            Set<String> methods = builder.overview(model).nodes().stream()
                    .filter(n -> n.kind() == NodeKind.METHOD || n.kind() == NodeKind.CONSTRUCTOR)
                    .map(GraphNode::id).collect(Collectors.toSet());
            for (String m : methods) {
                Graph g = builder.methodFlow(model, m);
                assertWellFormed(g, m);
            }
        }
    }
}
