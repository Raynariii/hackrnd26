package io.blueprints.core;

import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.EdgeKind;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.graph.NodeKind;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import io.blueprints.core.ops.DefaultGraphOperations;
import io.blueprints.core.ops.GraphOp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Члены, которых раньше не было на обзоре: инициализаторы, компактный конструктор, константы enum, анонимные классы. */
class OverviewMembersTest {

    private static final String SOURCE = """
            import java.util.*;

            public class Main {
                static final Map<String, Integer> CODES = new HashMap<>();
                int x, y;

                static {
                    CODES.put("a", 1);
                    log("static");
                }

                {
                    x = 1;
                }

                record Student(String name, int grade) {
                    Student {
                        if (grade < 0) {
                            throw new IllegalArgumentException("grade");
                        }
                    }
                }

                enum Kind {
                    QUIZ {
                        int weight() {
                            return 1;
                        }
                    },
                    EXAM {
                        int weight() {
                            return bonus() + 5;
                        }
                    },
                    PLAIN;

                    int weight() {
                        return 0;
                    }

                    static int bonus() {
                        return 2;
                    }
                }

                static void log(String s) {
                    System.out.println(s);
                }

                static List<String> sorted(List<String> xs) {
                    List<String> copy = new ArrayList<>(xs);
                    copy.sort(new Comparator<String>() {
                        @Override
                        public int compare(String a, String b) {
                            log("cmp");
                            return a.compareTo(b);
                        }
                    });
                    return copy;
                }
            }
            """;

    private final DefaultGraphBuilder builder = new DefaultGraphBuilder();

    private static ProjectModel parse() {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", SOURCE)));
        assertThat(model.problems()).isEmpty();
        return model;
    }

    private static GraphNode node(Graph g, String label) {
        return g.nodes().stream().filter(n -> n.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("нет узла " + label));
    }

    @Test
    void overviewShowsInitializersCompactConstructorEnumBodiesAndAnonymousClasses() {
        Graph g = builder.overview(parse());
        Map<String, GraphNode> byId = g.nodes().stream().collect(Collectors.toMap(GraphNode::id, Function.identity()));
        Function<GraphNode, String> parentLabel = n -> n.parent() == null ? null : byId.get(n.parent()).label();

        GraphNode staticInit = node(g, "static {}");
        assertThat(staticInit.kind()).isEqualTo(NodeKind.METHOD);
        assertThat(staticInit.meta()).containsEntry("initializer", "static");
        assertThat(parentLabel.apply(staticInit)).isEqualTo("Main");
        assertThat(node(g, "{}").meta()).containsEntry("initializer", "instance");

        GraphNode compact = g.nodes().stream()
                .filter(n -> n.kind() == NodeKind.CONSTRUCTOR && n.label().equals("Student")).findFirst().orElseThrow();
        assertThat(compact.meta()).containsEntry("compactConstructor", true);
        assertThat(parentLabel.apply(compact)).isEqualTo("Student");

        GraphNode quiz = node(g, "QUIZ");
        assertThat(quiz.kind()).isEqualTo(NodeKind.CLASS);
        assertThat(quiz.meta()).containsEntry("enumConstant", true);
        assertThat(parentLabel.apply(quiz)).isEqualTo("Kind");
        assertThat(g.nodes()).filteredOn(n -> n.label().equals("weight")).extracting(parentLabel)
                .containsExactlyInAnyOrder("Kind", "QUIZ", "EXAM");
        assertThat(g.nodes()).extracting(GraphNode::label).doesNotContain("PLAIN"); // без тела — не рамка

        GraphNode anon = node(g, "new Comparator<String>() {…}");
        assertThat(anon.meta()).containsEntry("anonymous", true);
        assertThat(parentLabel.apply(anon)).isEqualTo("sorted");
        assertThat(parentLabel.apply(node(g, "compare"))).isEqualTo(anon.label());

        assertThat(node(g, "x, y").kind()).isEqualTo(NodeKind.FIELD);

        // contains для каждой рамки и рёбра call из новых членов
        assertThat(g.edges()).filteredOn(e -> e.kind() == EdgeKind.CONTAINS)
                .anyMatch(e -> e.from().equals(anon.id()) && e.to().equals(node(g, "compare").id()));
        Map<String, String> labels = g.nodes().stream().collect(Collectors.toMap(GraphNode::id, GraphNode::label));
        assertThat(g.edges()).filteredOn(e -> e.kind() == EdgeKind.CALL)
                .extracting(e -> labels.get(e.from()) + " -> " + labels.get(e.to()))
                .contains("static {} -> log", "compare -> log");
        assertThat(g.edges()).filteredOn(e -> e.kind() == EdgeKind.CALL)
                .anyMatch(e -> labels.get(e.to()).equals("bonus"));
    }

    @Test
    void newMembersHaveFlowsAndAcceptInsertAtStart() {
        ProjectModel model = parse();
        Graph g = builder.overview(model);

        Graph init = builder.methodFlow(model, node(g, "static {}").id());
        assertThat(init.nodes().get(0).label()).isEqualTo("Инициализатор");
        assertThat(init.nodes()).extracting(GraphNode::kind).contains(NodeKind.CALL, NodeKind.END);

        String compactId = g.nodes().stream()
                .filter(n -> n.kind() == NodeKind.CONSTRUCTOR && n.label().equals("Student")).findFirst().orElseThrow().id();
        Graph compact = builder.methodFlow(model, compactId);
        assertThat(compact.nodes()).extracting(GraphNode::kind).contains(NodeKind.CONDITION, NodeKind.THROW);

        Graph compare = builder.methodFlow(model, node(g, "compare").id());
        assertThat(compare.nodes()).extracting(GraphNode::kind).contains(NodeKind.CALL, NodeKind.RETURN);

        new DefaultGraphOperations().apply(model, new GraphOp("insertAfter", node(g, "static {}").id() + ":start",
                null, "CODES.put(\"z\", 26);", null, null, null));
        // после операции id меняются — берём узел из нового графа
        String compactAfter = builder.overview(model).nodes().stream()
                .filter(n -> n.kind() == NodeKind.CONSTRUCTOR && n.label().equals("Student")).findFirst().orElseThrow().id();
        new DefaultGraphOperations().apply(model, new GraphOp("insertAfter", compactAfter + ":start",
                null, "name = name.strip();", null, null, null));
        String src = new CodeGenerator().print(model).get(0).content();
        assertThat(src).contains("    static {\n        CODES.put(\"z\", 26);\n        CODES.put(\"a\", 1);");
        assertThat(src).contains("        Student {\n            name = name.strip();\n            if (grade < 0) {");
    }
}
