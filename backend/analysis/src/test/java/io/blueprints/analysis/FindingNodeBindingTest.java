package io.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка стыка «находка анализатора ↔ узел графа» (роль 2 ↔ роль 1).
 *
 * <p>Находка обязана подсвечиваться на блок-схеме: каждый {@code Finding.nodeIds} должен указывать
 * либо на узел method-flow графа, либо на узел overview, либо попадать в {@code meta.covers} узла —
 * так ядро привязывает выражения (вызовы, присваивания) к блоку, в котором они лежат.
 *
 * <p>Тест держит контракт из {@code contracts/graph.schema.json} и ломается, если одна из сторон
 * отступит от соглашения {@code "g" + astId}.
 */
class FindingNodeBindingTest {

    private static String fixture() throws Exception {
        try (InputStream stream = FindingNodeBindingTest.class.getResourceAsStream("/monolith/OrdersMonolith.java")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyFindingPointsToGraphNode() throws Exception {
        ProjectModel model = new JavaProjectParser()
                .parse(List.of(new SourceFile("OrdersMonolith.java", fixture())));
        AnalysisReport report = Analyzer.withDefaultRules().analyze(model);
        DefaultGraphBuilder builder = new DefaultGraphBuilder();
        Graph overview = builder.overview(model);

        assertThat(report.findings()).isNotEmpty();
        for (Finding finding : report.findings()) {
            Set<String> reachable = reachableNodes(model, builder, overview, finding);
            assertThat(finding.nodeIds())
                    .as("находка %s (%s) должна ссылаться на узел графа: %s",
                            finding.id(), finding.title(), finding.nodeIds())
                    .anyMatch(reachable::contains);
        }
    }

    /** Узлы overview + method-flow того метода, внутри которого стоит находка, + их {@code meta.covers}. */
    private static Set<String> reachableNodes(ProjectModel model, DefaultGraphBuilder builder, Graph overview,
                                              Finding finding) {
        Set<String> reachable = new HashSet<>();
        collect(overview, reachable);
        Node anchor = model.nodeById(finding.nodeIds().get(0).substring(1)).orElse(null);
        if (anchor != null) {
            anchor.findAncestor(MethodDeclaration.class).ifPresent(method -> {
                String methodNodeId = "g" + model.idOf(method);
                Graph flow = builder.methodFlow(model, methodNodeId);
                collect(flow, reachable);
            });
        }
        return reachable;
    }

    private static void collect(Graph graph, Set<String> target) {
        for (GraphNode node : graph.nodes()) {
            target.add(node.id());
            for (Map.Entry<String, Object> entry : node.meta().entrySet()) {
                if ("covers".equals(entry.getKey()) && entry.getValue() instanceof List<?> covers) {
                    covers.forEach(id -> target.add(String.valueOf(id)));
                }
            }
        }
    }
}
