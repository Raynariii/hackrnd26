package io.blueprints.core;

import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.graph.NodeKind;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import io.blueprints.core.ops.DefaultGraphOperations;
import io.blueprints.core.ops.GraphOp;
import io.blueprints.core.ops.UnsupportedGraphOpException;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphOperationsTest {

    /** Метод keep с «нестандартным» форматированием и комментариями — проверяем, что правки его не трогают. */
    private static final String KEEP = """
                // не трогать
                static int keep( int a ,int b ) {   return a+b ; } // хвост
            """;

    private final DefaultGraphBuilder builder = new DefaultGraphBuilder();
    private final DefaultGraphOperations ops = new DefaultGraphOperations();
    private final CodeGenerator generator = new CodeGenerator();

    // ------------------------------------------------------------ helpers

    /** Проект из одного класса T: тело класса = body + метод keep. */
    private static final class Fixture {
        final ProjectModel model;

        Fixture(String body) {
            String src = "import java.util.*;\nimport java.io.*;\n\npublic class T {\n" + body + "\n" + KEEP + "}\n";
            model = new JavaProjectParser().parse(List.of(new SourceFile("T.java", src)));
            assertThat(model.problems()).as("исходник теста должен разбираться").isEmpty();
        }
    }

    private String methodId(Fixture f, String name) {
        return builder.overview(f.model).nodes().stream()
                .filter(n -> (n.kind() == NodeKind.METHOD || n.kind() == NodeKind.CONSTRUCTOR) && n.label().equals(name))
                .findFirst().orElseThrow().id();
    }

    private GraphNode overviewNode(Fixture f, NodeKind kind, String label) {
        return builder.overview(f.model).nodes().stream()
                .filter(n -> n.kind() == kind && n.label().equals(label)).findFirst().orElseThrow();
    }

    private GraphNode block(Fixture f, String method, NodeKind kind, String codePart) {
        Graph g = builder.methodFlow(f.model, methodId(f, method));
        return g.nodes().stream().filter(n -> n.kind() == kind && n.code() != null && n.code().contains(codePart))
                .findFirst().orElseThrow(() -> new AssertionError("нет блока " + kind + " с " + codePart));
    }

    private List<GraphNode> blocks(Fixture f, String method, NodeKind kind) {
        return builder.methodFlow(f.model, methodId(f, method)).nodes().stream().filter(n -> n.kind() == kind).toList();
    }

    private static GraphOp edit(String nodeId, String code) {
        return new GraphOp("editNode", nodeId, null, code, null, null, null);
    }

    private static GraphOp insert(String nodeId, String code) {
        return insert(nodeId, null, code);
    }

    private static GraphOp insert(String nodeId, NodeKind kind, String code) {
        return new GraphOp("insertAfter", nodeId, null, code, kind, null, null);
    }

    private static GraphOp delete(String nodeId) {
        return new GraphOp("delete", nodeId, null, null, null, null, null);
    }

    /** Применить, напечатать, убедиться, что код разбирается, компилируется и keep не тронут. */
    private String apply(Fixture f, GraphOp op) {
        ops.apply(f.model, op);
        CodeGenerator.Printed printed = generator.printReport(f.model);
        assertThat(printed.reformatted()).as("LexicalPreservingPrinter не должен падать").isEmpty();
        String src = printed.files().get(0).content();
        assertThat(src).as("нетронутый код печатается байт-в-байт").contains(KEEP);
        assertThat(new JavaProjectParser().parse(List.of(new SourceFile("T.java", src))).problems())
                .as("результат разбирается:\n" + src).isEmpty();
        assertThat(compile(src)).as("результат компилируется:\n" + src).isEmpty();
        return src;
    }

    private static String compile(String src) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///T.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return src;
            }
        };
        StringWriter out = new StringWriter();
        boolean ok = javac.getTask(out, null, null, List.of("-d", System.getProperty("java.io.tmpdir") + "/ops-test",
                "-proc:none"), null, List.of(file)).call();
        return ok ? "" : out.toString();
    }

    // ------------------------------------------------------------ editNode

    @Test
    void editReplacesStatementAndKeepsRestOfFile() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    x = x + 1;   // комментарий остаётся
                    return x;
                }""");
        String src = apply(f, edit(block(f, "f", NodeKind.OPERATION, "x = x + 1").id(), "x += 2"));

        assertThat(src).contains("x += 2;").doesNotContain("x = x + 1").contains("return x;");
    }

    @Test
    void editStatementWithSeveralStatements() {
        Fixture f = new Fixture("""
                static void f() {
                    System.out.println(1);
                }""");
        String src = apply(f, edit(block(f, "f", NodeKind.CALL, "println(1)").id(),
                "int y = 2;\nSystem.out.println(y);"));

        assertThat(src).contains("int y = 2;").contains("System.out.println(y);").doesNotContain("println(1)");
    }

    @Test
    void editConditionChangesOnlyHeader() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    if (x > 0) {
                        x--;   // тело на месте
                    }
                    return x;
                }""");
        String src = apply(f, edit(block(f, "f", NodeKind.CONDITION, "x > 0").id(), "x >= 10"));

        assertThat(src).contains("if (x >= 10) {").contains("x--;   // тело на месте");
    }

    @Test
    void editConditionAcceptsFullIfHeaderButRejectsOtherStatements() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    if (x > 0) {
                        x--;
                    }
                    return x;
                }""");
        String id = block(f, "f", NodeKind.CONDITION, "x > 0").id();
        assertThatThrownBy(() -> ops.apply(f.model, edit(id, "System.out.println(x);")))
                .isInstanceOf(UnsupportedGraphOpException.class).hasMessageContaining("условие");

        Fixture g = new Fixture("""
                static int f(int x) {
                    if (x > 0) {
                        x--;
                    }
                    return x;
                }""");
        String src = apply(g, edit(block(g, "f", NodeKind.CONDITION, "x > 0").id(), "if (x != 0)"));
        assertThat(src).contains("if (x != 0) {").contains("x--;");
    }

    @Test
    void editLoopHeaders() {
        Fixture f = new Fixture("""
                static int f(List<Integer> xs) {
                    int s = 0;
                    for (int i = 0; i < xs.size(); i++) {
                        s += xs.get(i);
                    }
                    while (s > 100) {
                        s /= 2;
                    }
                    return s;
                }""");
        apply(f, edit(block(f, "f", NodeKind.LOOP, "while").id(), "s > 1000"));
        String src = apply(f, edit(block(f, "f", NodeKind.LOOP, "int i = 0").id(), "int i = 1; i < xs.size(); i += 2"));

        assertThat(src).contains("while (s > 1000) {").contains("for (int i = 1; i < xs.size(); i += 2) {")
                .contains("s += xs.get(i);");
    }

    @Test
    void editForToForEachMovesBody() {
        Fixture f = new Fixture("""
                static int f(List<Integer> xs) {
                    int s = 0;
                    for (int i = 0; i < 3; i++) {
                        s += 1;
                    }
                    return s;
                }""");
        String src = apply(f, edit(block(f, "f", NodeKind.LOOP, "int i").id(), "for (int x : xs)"));

        assertThat(src).contains("for (int x : xs)").contains("s += 1;").doesNotContain("i++");
        assertThat(block(f, "f", NodeKind.LOOP, "xs").meta()).containsEntry("loopKind", "for-each");
    }

    @Test
    void editSwitchCaseAndCatch() {
        Fixture f = new Fixture("""
                static String f(int d, String s) {
                    switch (d) {
                        case 1:
                            return "one";
                        default:
                            return "many";
                    }
                }
                static int g(String s) {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }""");
        apply(f, edit(block(f, "f", NodeKind.SWITCH, "d").id(), "d % 10"));
        apply(f, edit(block(f, "f", NodeKind.CASE, "case 1").id(), "case 1, 2"));
        String src = apply(f, edit(block(f, "g", NodeKind.CATCH, "NumberFormatException").id(),
                "catch (RuntimeException e)"));

        assertThat(src).contains("switch (d % 10)").contains("case 1, 2:").contains("catch (RuntimeException e)");
    }

    @Test
    void editByExpressionIdTargetsEnclosingStatement() {
        Fixture f = new Fixture("""
                static void f(List<Integer> xs) {
                    System.out.println(xs.contains(1));
                }""");
        GraphNode call = block(f, "f", NodeKind.CALL, "contains");
        @SuppressWarnings("unchecked")
        List<String> covers = (List<String>) call.meta().get("covers");
        String src = apply(f, edit(covers.get(covers.size() - 1), "System.out.println(xs.isEmpty())"));

        assertThat(src).contains("System.out.println(xs.isEmpty());").doesNotContain("contains(1)");
    }

    @Test
    void editRejectsBrokenCode() {
        Fixture f = new Fixture("""
                static void f() {
                    int a = 1;
                }""");
        String id = block(f, "f", NodeKind.VARIABLE, "a = 1").id();

        assertThatThrownBy(() -> ops.apply(f.model, edit(id, "int = ;")))
                .isInstanceOf(UnsupportedGraphOpException.class).hasMessageContaining("Код не разбирается");
        assertThatThrownBy(() -> ops.apply(f.model, edit(id, " ")))
                .isInstanceOf(UnsupportedGraphOpException.class);
    }

    // ------------------------------------------------------------ insertAfter

    @Test
    void insertAfterStatementAndStart() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    x++;
                    return x;
                }""");
        apply(f, insert(block(f, "f", NodeKind.OPERATION, "x++").id(), "x *= 2;"));
        String src = apply(f, insert(methodId(f, "f") + ":start", "System.out.println(\"start\")"));

        assertThat(src.indexOf("println(\"start\")")).isLessThan(src.indexOf("x++"));
        assertThat(src.indexOf("x++")).isLessThan(src.indexOf("x *= 2;"));
        assertThat(src.indexOf("x *= 2;")).isLessThan(src.indexOf("return x;"));
        // Блок-схема пересобирается из нового AST: новые узлы на месте.
        assertThat(blocks(f, "f", NodeKind.OPERATION)).extracting(GraphNode::code).containsExactly("x++", "x *= 2");
    }

    @Test
    void insertAtStartOfConstructorGoesAfterSuperCall() {
        Fixture f = new Fixture("""
                int v;
                T() {
                    super();
                    v = 1;
                }""");
        String src = apply(f, insert(methodId(f, "T") + ":start", "v = 0;"));

        assertThat(src.indexOf("super();")).isLessThan(src.indexOf("v = 0;"));
    }

    @Test
    void insertAfterContainerGoesAfterWholeConstruct() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    while (x > 10) {
                        x /= 2;
                    }
                    return x;
                }""");
        String src = apply(f, insert(block(f, "f", NodeKind.LOOP, "while").id(), "x += 1;"));

        assertThat(src.indexOf("}", src.indexOf("x /= 2;"))).isLessThan(src.indexOf("x += 1;"));
        GraphNode added = block(f, "f", NodeKind.OPERATION, "x += 1");
        assertThat(added.parent()).isNull();
    }

    @Test
    void insertIntoBracelessBodyWrapsBlock() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    if (x > 0) x--;
                    return x;
                }""");
        String src = apply(f, insert(block(f, "f", NodeKind.OPERATION, "x--").id(), "x--;"));

        GraphNode cond = block(f, "f", NodeKind.CONDITION, "x > 0");
        assertThat(blocks(f, "f", NodeKind.OPERATION)).hasSize(2).allMatch(n -> cond.id().equals(n.parent()));
        assertThat(src).contains("{");
    }

    @Test
    void insertIntoCaseCatchAndFinally() {
        Fixture f = new Fixture("""
                static int f(int d) {
                    switch (d) {
                        case 1 -> System.out.println("one");
                        default -> {
                            System.out.println("other");
                        }
                    }
                    try {
                        d = 10 / d;
                    } catch (ArithmeticException e) {
                        d = 0;
                    } finally {
                        System.out.println("done");
                    }
                    return d;
                }""");
        apply(f, insert(block(f, "f", NodeKind.CASE, "case 1").id(), "d++;"));
        apply(f, insert(block(f, "f", NodeKind.CASE, "default").id(), "d--;"));
        apply(f, insert(block(f, "f", NodeKind.CATCH, "ArithmeticException").id(), "System.out.println(\"div\");"));
        String src = apply(f, insert(block(f, "f", NodeKind.FINALLY, "finally").id(), "d += 100;"));

        GraphNode case1 = block(f, "f", NodeKind.CASE, "case 1");
        assertThat(block(f, "f", NodeKind.OPERATION, "d++").parent()).isEqualTo(case1.id());
        assertThat(block(f, "f", NodeKind.OPERATION, "d--").parent())
                .isEqualTo(block(f, "f", NodeKind.CASE, "default").id());
        assertThat(block(f, "f", NodeKind.CALL, "\"div\"").parent())
                .isEqualTo(block(f, "f", NodeKind.CATCH, "ArithmeticException").id());
        assertThat(block(f, "f", NodeKind.OPERATION, "d += 100").parent())
                .isEqualTo(block(f, "f", NodeKind.FINALLY, "finally").id());
        assertThat(src.indexOf("d++;")).isLessThan(src.indexOf("\"one\""));
    }

    @Test
    void insertFromPaletteByKind() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    x++;
                    return x;
                }""");
        String anchor = block(f, "f", NodeKind.OPERATION, "x++").id();
        apply(f, insert(anchor, NodeKind.CONDITION, "x > 5"));
        apply(f, insert(block(f, "f", NodeKind.OPERATION, "x++").id(), NodeKind.LOOP, "int i = 0; i < 3; i++"));
        String src = apply(f, insert(block(f, "f", NodeKind.OPERATION, "x++").id(), NodeKind.VARIABLE, "int y = x"));

        assertThat(src).contains("if (x > 5) {").contains("for (int i = 0; i < 3; i++) {").contains("int y = x;");
        assertThat(blocks(f, "f", NodeKind.CONDITION)).extracting(GraphNode::code).containsExactly("x > 5");
        assertThat(blocks(f, "f", NodeKind.LOOP)).hasSize(1);
    }

    @Test
    void insertAfterEndIsRejected() {
        Fixture f = new Fixture("static void f() {}");

        assertThatThrownBy(() -> ops.apply(f.model, insert(methodId(f, "f") + ":end", "int a = 1;")))
                .isInstanceOf(UnsupportedGraphOpException.class);
    }

    // ------------------------------------------------------------ delete

    @Test
    void deleteRemovesTheRightOneOfIdenticalStatements() {
        Fixture f = new Fixture("""
                static int f(int i) {
                    i++; // первый
                    i++; // второй
                    return i;
                }""");
        List<GraphNode> incs = blocks(f, "f", NodeKind.OPERATION);
        String src = apply(f, delete(incs.get(1).id()));

        assertThat(src).contains("i++; // первый").doesNotContain("i++; // второй");
    }

    @Test
    void deleteContainerWithBody() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    if (x > 0) {
                        x--;
                    }
                    return x;
                }""");
        String src = apply(f, delete(block(f, "f", NodeKind.CONDITION, "x > 0").id()));

        assertThat(src).doesNotContain("if (").doesNotContain("x--");
    }

    @Test
    void deleteBracelessBodyAndElse() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    while (x > 10) x /= 2;
                    if (x > 0) x--; else x++;
                    return x;
                }""");
        apply(f, delete(block(f, "f", NodeKind.OPERATION, "x /= 2").id()));
        String src = apply(f, delete(block(f, "f", NodeKind.OPERATION, "x++").id()));

        assertThat(src).doesNotContain("x /= 2").doesNotContain("else").contains("x--");
    }

    @Test
    void deleteCatchCaseAndFinally() {
        Fixture f = new Fixture("""
                static int f(int d) {
                    switch (d) {
                        case 1: d = 5; break;
                        case 2: d = 6; break;
                    }
                    try {
                        d = 10 / d;
                    } catch (ArithmeticException e) {
                        d = 0;
                    } finally {
                        d++;
                    }
                    return d;
                }""");
        apply(f, delete(block(f, "f", NodeKind.CASE, "case 2").id()));
        apply(f, delete(block(f, "f", NodeKind.CATCH, "ArithmeticException").id()));
        String finId = block(f, "f", NodeKind.FINALLY, "finally").id();
        assertThatThrownBy(() -> ops.apply(f.model, delete(finId)))
                .isInstanceOf(UnsupportedGraphOpException.class).hasMessageContaining("try");

        Fixture g = new Fixture("""
                static int f(int d) {
                    switch (d) {
                        case 1: d = 5; break;
                        case 2: d = 6; break;
                    }
                    try {
                        d = 10 / d;
                    } catch (ArithmeticException e) {
                        d = 0;
                    } finally {
                        d++;
                    }
                    return d;
                }""");
        apply(g, delete(block(g, "f", NodeKind.CASE, "case 2").id()));
        String src = apply(g, delete(block(g, "f", NodeKind.FINALLY, "finally").id()));
        assertThat(src).doesNotContain("case 2").doesNotContain("finally").contains("catch (ArithmeticException e)");
    }

    @Test
    void deleteSeveralNodesAtOnce() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    x++;
                    x--;
                    x *= 3;
                    return x;
                }""");
        List<String> ids = blocks(f, "f", NodeKind.OPERATION).stream().limit(2).map(GraphNode::id).toList();
        String src = apply(f, new GraphOp("delete", null, ids, null, null, null, null));

        assertThat(src).doesNotContain("x++").doesNotContain("x--").contains("x *= 3;");
    }

    @Test
    void deleteStartOrUnknownNodeIsRejected() {
        Fixture f = new Fixture("static void f() {}");

        assertThatThrownBy(() -> ops.apply(f.model, delete(methodId(f, "f") + ":start")))
                .isInstanceOf(UnsupportedGraphOpException.class);
        assertThatThrownBy(() -> ops.apply(f.model, delete("g999999")))
                .isInstanceOf(UnsupportedGraphOpException.class).hasMessageContaining("не найден");
        assertThatThrownBy(() -> ops.apply(f.model, new GraphOp("move", "g1", null, null, null, null, null)))
                .isInstanceOf(UnsupportedGraphOpException.class).hasMessageContaining("ещё не реализована");
    }

    // ------------------------------------------------------------ члены класса (обзорный граф)

    @Test
    void editInsertAndDeleteMembers() {
        Fixture f = new Fixture("""
                static int counter = 0;
                static int twice(int x) {
                    return x * 2;
                }
                static void unused() {
                }""");
        apply(f, edit(overviewNode(f, NodeKind.METHOD, "twice").id(),
                "static int twice(int x) {\n        return x + x;\n    }"));
        apply(f, insert(overviewNode(f, NodeKind.FIELD, "counter").id(), "static final int LIMIT = 10;"));
        String src = apply(f, delete(overviewNode(f, NodeKind.METHOD, "unused").id()));

        assertThat(src).contains("return x + x;").contains("static final int LIMIT = 10;").doesNotContain("unused");
        assertThat(src.indexOf("counter")).isLessThan(src.indexOf("LIMIT"));
    }

    // ------------------------------------------------------------ форматирование и откат

    @Test
    void insertedCodeIsIndentedLikeNeighbours() {
        Fixture f = new Fixture("""
                    static int f(int x) {
                        x++;
                        if (x > 0) {
                            x--;
                        }
                        return x;
                    }""");
        apply(f, insert(methodId(f, "f") + ":start", "x = Math.abs(x);"));
        apply(f, insert(block(f, "f", NodeKind.OPERATION, "x--").id(), "for (int i = 0; i < 2; i++) {\n    x += i;\n}"));
        String src = apply(f, insert(block(f, "f", NodeKind.CONDITION, "x > 0").id(), NodeKind.CONDITION, "x > 9"));

        assertThat(src).contains("""
                    static int f(int x) {
                        x = Math.abs(x);
                        x++;
                        if (x > 0) {
                            x--;
                            for (int i = 0; i < 2; i++) {
                                x += i;
                            }
                        }
                        if (x > 9) {
                        }
                        return x;
                    }""");
    }

    @Test
    void keepsTabsAndWindowsLineEndings() {
        String src = "public class T {\r\n\tstatic int f(int x) {\r\n\t\tx++;\r\n\t\treturn x;\r\n\t}\r\n}\r\n";
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("T.java", src)));
        String id = builder.overview(model).nodes().stream().filter(n -> n.label().equals("f")).findFirst().orElseThrow().id();
        String inc = builder.methodFlow(model, id).nodes().stream()
                .filter(n -> n.kind() == NodeKind.OPERATION).findFirst().orElseThrow().id();

        ops.apply(model, insert(inc, "x *= 2;"));

        assertThat(generator.print(model).get(0).content())
                .isEqualTo("public class T {\r\n\tstatic int f(int x) {\r\n\t\tx++;\r\n\t\tx *= 2;\r\n\t\treturn x;\r\n\t}\r\n}\r\n");
    }

    @Test
    void failedOperationLeavesModelUntouched() {
        Fixture f = new Fixture("""
                static void f() {
                    int a = 1;
                }""");
        String before = generator.print(f.model).get(0).content();
        String id = block(f, "f", NodeKind.VARIABLE, "a = 1").id();

        assertThatThrownBy(() -> ops.apply(f.model, insert(id, NodeKind.CONDITION, "a > (0")))
                .isInstanceOf(UnsupportedGraphOpException.class);
        assertThat(generator.print(f.model).get(0).content()).isEqualTo(before);
        assertThat(block(f, "f", NodeKind.VARIABLE, "a = 1").id()).isEqualTo(id);
    }

    @Test
    void deleteContainerTogetherWithItsChild() {
        Fixture f = new Fixture("""
                static int f(int x) {
                    while (x > 10) {
                        x /= 2;
                    }
                    return x;
                }""");
        String loop = block(f, "f", NodeKind.LOOP, "while").id();
        String inner = block(f, "f", NodeKind.OPERATION, "x /= 2").id();
        String src = apply(f, new GraphOp("delete", null, List.of(inner, loop), null, null, null, null));

        assertThat(src).contains("""
                static int f(int x) {
                    return x;
                }""");
    }
}
