package io.blueprints.core;

import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoundTripTest {

    private static final String SOURCE = """
            import java.util.*;

            public class Main {
                private static int counter = 0;

                public static void main(String[] args) {
                    List<Integer> xs = List.of(3, 1, 2);
                    for (int x : xs) {
                        if (x > 1) {
                            counter += x;
                        }
                    }
                    System.out.println(counter);
                }
            }
            """;

    @Test
    void printsUnchangedSourceByteForByte() {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", SOURCE)));

        assertThat(model.hasErrors()).isFalse();
        assertThat(new CodeGenerator().print(model)).containsExactly(new SourceFile("Main.java", SOURCE));
    }

    @Test
    void overviewContainsClassAndMembers() {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Main.java", SOURCE)));

        Graph graph = new DefaultGraphBuilder().overview(model);

        assertThat(graph.nodes()).extracting(n -> n.kind() + ":" + n.label())
                .containsExactly("CLASS:Main", "FIELD:counter", "METHOD:main");
        assertThat(graph.edges()).hasSize(2);
    }

    @Test
    void reportsParseProblems() {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Bad.java", "class Bad { void f( }")));

        assertThat(model.problems()).isNotEmpty();
        assertThat(model.problems().get(0).file()).isEqualTo("Bad.java");
    }
}
