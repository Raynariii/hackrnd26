package io.blueprints.core.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Overview = типы + их члены (рёбра contains) + рёбра call ({@link CallGraph}). methodFlow — {@link MethodFlowBuilder}.
 */
public final class DefaultGraphBuilder implements GraphBuilder {

    @Override
    public Graph overview(ProjectModel model) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                String typeId = "g" + model.idOf(type);
                nodes.add(node(model, file, type, typeId, typeKind(type), type.getNameAsString(), null,
                        type.getParentNode().filter(p -> p instanceof TypeDeclaration<?>)
                                .map(p -> "g" + model.idOf(p)).orElse(null)));
                for (Node member : type.getMembers()) {
                    String label;
                    NodeKind kind;
                    if (member instanceof MethodDeclaration m) {
                        kind = NodeKind.METHOD;
                        label = m.getNameAsString();
                    } else if (member instanceof ConstructorDeclaration c) {
                        kind = NodeKind.CONSTRUCTOR;
                        label = c.getNameAsString();
                    } else if (member instanceof FieldDeclaration f) {
                        kind = NodeKind.FIELD;
                        label = f.getVariables().get(0).getNameAsString();
                    } else {
                        continue;
                    }
                    String code = member instanceof CallableDeclaration<?> c
                            ? c.getDeclarationAsString(true, true, true)
                            : member.toString();
                    String memberId = "g" + model.idOf(member);
                    nodes.add(node(model, file, member, memberId, kind, label, code, typeId));
                    edges.add(new GraphEdge("e" + typeId + "-" + memberId, typeId, memberId, EdgeKind.CONTAINS, null));
                }
            }
        });
        edges.addAll(CallGraph.edges(model));
        return new Graph(GraphLevel.OVERVIEW, null, nodes, edges);
    }

    @Override
    public Graph methodFlow(ProjectModel model, String methodNodeId) {
        return MethodFlowBuilder.build(model, methodNodeId);
    }

    private static GraphNode node(ProjectModel model, String file, Node ast, String id, NodeKind kind,
                                  String label, String code, String parent) {
        SourceRange range = ast.getRange()
                .map(r -> new SourceRange(file, r.begin.line, r.end.line, r.begin.column, r.end.column))
                .orElse(null);
        return new GraphNode(id, model.idOf(ast), kind, label, code, range, parent, List.of(), Map.of());
    }

    private static NodeKind typeKind(TypeDeclaration<?> type) {
        if (type.isEnumDeclaration()) {
            return NodeKind.ENUM;
        }
        if (type.isRecordDeclaration()) {
            return NodeKind.RECORD;
        }
        if (type.isClassOrInterfaceDeclaration() && type.asClassOrInterfaceDeclaration().isInterface()) {
            return NodeKind.INTERFACE;
        }
        return NodeKind.CLASS;
    }
}
