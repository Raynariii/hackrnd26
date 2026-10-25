package io.blueprints.core.graph;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overview = типы + их члены (рёбра contains, правило — {@link Members}) + рёбра call ({@link CallGraph}).
 * Константы enum со своим телом и анонимные классы — рамки (kind class) со своими методами. methodFlow —
 * {@link MethodFlowBuilder}.
 */
public final class DefaultGraphBuilder implements GraphBuilder {

    @Override
    public Graph overview(ProjectModel model) {
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                String typeId = "g" + model.idOf(type);
                String parent = containerOf(model, type);
                nodes.add(node(model, file, type, typeId, typeKind(type), type.getNameAsString(), null, parent, Map.of()));
                contains(edges, parent, typeId);
                members(model, file, type.getMembers(), typeId, nodes, edges);
                if (type instanceof EnumDeclaration e) {
                    for (EnumConstantDeclaration c : e.getEntries()) {
                        if (c.getClassBody().isEmpty()) {
                            continue;
                        }
                        // Константа enum со своим телом — рамка с её методами.
                        String id = "g" + model.idOf(c);
                        nodes.add(node(model, file, c, id, NodeKind.CLASS, c.getNameAsString(), null, typeId,
                                Map.of("enumConstant", true)));
                        contains(edges, typeId, id);
                        members(model, file, c.getClassBody(), id, nodes, edges);
                    }
                }
            }
            for (ObjectCreationExpr o : cu.findAll(ObjectCreationExpr.class)) {
                if (o.getAnonymousClassBody().isEmpty()) {
                    continue;
                }
                // Анонимный класс — рамка с методами внутри члена, где он создан.
                String id = "g" + model.idOf(o);
                String parent = containerOf(model, o);
                nodes.add(node(model, file, o, id, NodeKind.CLASS, "new " + o.getType().asString() + "() {…}", null,
                        parent, Map.of("anonymous", true)));
                contains(edges, parent, id);
                members(model, file, o.getAnonymousClassBody().get(), id, nodes, edges);
            }
        });
        edges.addAll(CallGraph.edges(model));
        return new Graph(GraphLevel.OVERVIEW, null, nodes, edges);
    }

    private static void members(ProjectModel model, String file, List<? extends BodyDeclaration<?>> body,
                                String containerId, List<GraphNode> nodes, List<GraphEdge> edges) {
        for (BodyDeclaration<?> member : body) {
            if (!Members.isMember(member)) {
                continue; // вложенные типы и константы enum добавляются отдельно
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            MemberNotes.section(member).ifPresent(v -> meta.put("section", v));
            MemberNotes.doc(member).ifPresent(v -> meta.put("doc", v));
            if (MemberNotes.isEntryPoint(member)) {
                meta.put("entry", true);
            }
            if (member instanceof InitializerDeclaration i) {
                meta.put("initializer", i.isStatic() ? "static" : "instance");
            } else if (member instanceof CompactConstructorDeclaration) {
                meta.put("compactConstructor", true);
            }
            String memberId = "g" + model.idOf(member);
            nodes.add(node(model, file, member, memberId, Members.kind(member), Members.label(member),
                    Members.header(member), containerId, meta));
            contains(edges, containerId, memberId);
        }
    }

    private static void contains(List<GraphEdge> edges, String from, String to) {
        if (from != null) {
            edges.add(new GraphEdge("e" + from + "-" + to, from, to, EdgeKind.CONTAINS, null));
        }
    }

    /** Ближайший узел обзора над node: тип, член (для локальных и анонимных классов), константа enum, анонимный класс. */
    private static String containerOf(ProjectModel model, Node node) {
        Node n = node.getParentNode().orElse(null);
        while (n != null) {
            if (n instanceof TypeDeclaration<?> || Members.isMember(n)
                    || n instanceof EnumConstantDeclaration c && c.getClassBody().isNonEmpty()
                    || n instanceof ObjectCreationExpr o && o.getAnonymousClassBody().isPresent()) {
                return "g" + model.idOf(n);
            }
            n = n.getParentNode().orElse(null);
        }
        return null;
    }

    @Override
    public Graph methodFlow(ProjectModel model, String methodNodeId) {
        return MethodFlowBuilder.build(model, methodNodeId);
    }

    private static GraphNode node(ProjectModel model, String file, Node ast, String id, NodeKind kind,
                                  String label, String code, String parent, Map<String, Object> meta) {
        SourceRange range = ast.getRange()
                .map(r -> new SourceRange(file, r.begin.line, r.end.line, r.begin.column, r.end.column))
                .orElse(null);
        return new GraphNode(id, model.idOf(ast), kind, label, code, range, parent, List.of(), meta);
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
