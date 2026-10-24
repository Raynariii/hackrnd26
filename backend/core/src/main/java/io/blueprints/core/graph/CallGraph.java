package io.blueprints.core.graph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Рёбра {@code call} обзорного графа: член класса (метод, конструктор, поле с инициализатором) → вызываемый метод
 * или конструктор проекта; {@code new X()} без явного конструктора — ребро на класс X. Вызовы библиотек
 * (List.add, System.out.println) рёбер не дают.
 *
 * <p>Сначала вызов разрешается через SymbolSolver (точно, с учётом перегрузок и типов). Если он не справился
 * (незнакомая библиотека, сложный вывод типов), — откат на имена: метод с тем же именем и числом аргументов
 * в своём классе (для вызова без квалификатора или через this), в классе проекта с таким именем ({@code Parser.parse()})
 * или в классе объявленного типа переменной ({@code parser.parse()}).
 *
 * <p>Несколько вызовов одной пары сливаются в одно ребро, {@code label} = «×N» при N > 1.
 */
final class CallGraph {

    private final ProjectModel model;
    private final Map<String, List<TypeDeclaration<?>>> typesBySimpleName = new HashMap<>();
    /** Имена методов проекта: вызов с другим именем ребра не даст, его не разрешаем (SymbolSolver — дорогой). */
    private final Set<String> methodNames = new HashSet<>();
    private final JavaParserFacade facade;

    private CallGraph(ProjectModel model) {
        this.model = model;
        Map<String, TypeDeclaration<?>> byQualifiedName = new HashMap<>();
        for (CompilationUnit cu : model.units().values()) {
            for (TypeDeclaration<?> t : cu.findAll(TypeDeclaration.class)) {
                typesBySimpleName.computeIfAbsent(t.getNameAsString(), k -> new ArrayList<>()).add(t);
                t.getMethods().forEach(m -> methodNames.add(m.getNameAsString()));
                t.getFullyQualifiedName().ifPresent(n -> byQualifiedName.put(n, t));
            }
        }
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(), new ProjectTypeSolver(byQualifiedName));
        this.facade = JavaParserFacade.get(solver);
    }

    static List<GraphEdge> edges(ProjectModel model) {
        return new CallGraph(model).build();
    }

    private List<GraphEdge> build() {
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (CompilationUnit cu : model.units().values()) {
            for (Node call : cu.findAll(Node.class, n -> n instanceof MethodCallExpr || n instanceof ObjectCreationExpr
                    || n instanceof ExplicitConstructorInvocationStmt)) {
                Node owner = member(call);
                if (owner == null) {
                    continue;
                }
                for (Node target : targets(call)) {
                    String key = "g" + model.idOf(owner) + "|g" + model.idOf(target);
                    counts.computeIfAbsent(key, k -> new int[1])[0]++;
                }
            }
        }
        List<GraphEdge> edges = new ArrayList<>();
        counts.forEach((key, n) -> {
            String[] ends = key.split("\\|");
            edges.add(new GraphEdge("c" + ends[0] + "-" + ends[1], ends[0], ends[1], EdgeKind.CALL,
                    n[0] > 1 ? "×" + n[0] : null));
        });
        return edges;
    }

    /** Член, которому принадлежит вызов, — узел обзорного графа (метод анонимного класса — сам по себе). */
    private static Node member(Node node) {
        Node n = node.getParentNode().orElse(null);
        Node found = null;
        while (n != null) {
            if (isOverviewMember(n)) {
                found = n;
                break;
            }
            n = n.getParentNode().orElse(null);
        }
        return found;
    }

    private static boolean isOverviewMember(Node n) {
        return Members.isMember(n);
    }

    private List<Node> targets(Node call) {
        if (call instanceof MethodCallExpr m && !methodNames.contains(m.getNameAsString())
                || call instanceof ObjectCreationExpr o && !typesBySimpleName.containsKey(o.getType().getNameAsString())) {
            return List.of();
        }
        Optional<Node> solved = solve(call);
        if (solved.isPresent()) {
            Node target = solved.get();
            return isOverviewMember(target) || target instanceof TypeDeclaration<?> ? List.of(target) : List.of();
        }
        return byName(call);
    }

    private Optional<Node> solve(Node call) {
        try {
            SymbolReference<? extends ResolvedDeclaration> ref;
            if (call instanceof MethodCallExpr m) {
                ref = facade.solve(m);
            } else if (call instanceof ObjectCreationExpr o) {
                ref = facade.solve(o);
            } else {
                ref = facade.solve((ExplicitConstructorInvocationStmt) call);
            }
            if (ref.isSolved()) {
                return ref.getCorrespondingDeclaration().toAst().map(n -> (Node) n);
            }
        } catch (RuntimeException | StackOverflowError e) {
            // SymbolSolver не справился — ниже откат на имена.
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- откат на имена

    private List<Node> byName(Node call) {
        if (call instanceof MethodCallExpr m) {
            int arity = m.getArguments().size();
            List<TypeDeclaration<?>> owners = m.getScope().map(s -> scopeTypes(s, m)).orElseGet(() -> enclosingTypes(m));
            List<Node> found = new ArrayList<>();
            for (TypeDeclaration<?> t : owners) {
                for (MethodDeclaration md : t.getMethodsByName(m.getNameAsString())) {
                    if (accepts(md, arity)) {
                        found.add(md);
                    }
                }
                if (!found.isEmpty()) {
                    break; // ближайший класс перекрывает внешние
                }
            }
            return found;
        }
        if (call instanceof ObjectCreationExpr o) {
            if (o.getAnonymousClassBody().isPresent()) {
                return List.of();
            }
            List<TypeDeclaration<?>> types = typesBySimpleName.getOrDefault(o.getType().getNameAsString(), List.of());
            List<Node> found = constructors(types, o.getArguments().size());
            if (found.isEmpty() && o.getArguments().isEmpty()) {
                // Конструктор по умолчанию: узла нет — ребро ведёт на сам класс.
                types.stream().filter(t -> t.getConstructors().isEmpty()).forEach(found::add);
            }
            return found;
        }
        ExplicitConstructorInvocationStmt c = (ExplicitConstructorInvocationStmt) call;
        if (!c.isThis()) {
            return List.of();
        }
        return c.findAncestor(TypeDeclaration.class).map(t -> constructors(List.of(t), c.getArguments().size()))
                .orElse(List.of());
    }

    private static List<Node> constructors(List<TypeDeclaration<?>> types, int arity) {
        List<Node> found = new ArrayList<>();
        for (TypeDeclaration<?> t : types) {
            for (ConstructorDeclaration cd : t.getConstructors()) {
                if (accepts(cd, arity)) {
                    found.add(cd);
                }
            }
        }
        return found;
    }

    private static boolean accepts(CallableDeclaration<?> c, int arity) {
        int params = c.getParameters().size();
        boolean varArgs = params > 0 && c.getParameter(params - 1).isVarArgs();
        return varArgs ? arity >= params - 1 : arity == params;
    }

    /** Классы, в которых ищется метод вызова без квалификатора: свой и внешние, от ближнего к дальнему. */
    private static List<TypeDeclaration<?>> enclosingTypes(Node node) {
        List<TypeDeclaration<?>> types = new ArrayList<>();
        Node n = node.getParentNode().orElse(null);
        while (n != null) {
            if (n instanceof TypeDeclaration<?> t) {
                types.add(t);
            }
            n = n.getParentNode().orElse(null);
        }
        return types;
    }

    /** Тип квалификатора: this, имя класса проекта, переменная/поле/параметр с объявленным типом, new X(). */
    private List<TypeDeclaration<?>> scopeTypes(Expression scope, Node at) {
        if (scope instanceof ThisExpr) {
            return enclosingTypes(at);
        }
        if (scope instanceof ObjectCreationExpr o) {
            return typesBySimpleName.getOrDefault(o.getType().getNameAsString(), List.of());
        }
        String name = scope instanceof NameExpr n ? n.getNameAsString()
                : scope instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr ? f.getNameAsString() : null;
        if (name == null) {
            return List.of();
        }
        Optional<Type> declared = declaredType(name, at);
        if (declared.isPresent()) {
            return declared.get() instanceof ClassOrInterfaceType c
                    ? typesBySimpleName.getOrDefault(c.getNameAsString(), List.of()) : List.of();
        }
        return typesBySimpleName.getOrDefault(name, List.of()); // Parser.parse() — статический вызов
    }

    /** Объявленный тип имени: параметр или локальная переменная метода, затем поля своего и внешних классов. */
    private static Optional<Type> declaredType(String name, Node at) {
        Optional<CallableDeclaration> callable = at.findAncestor(CallableDeclaration.class);
        if (callable.isPresent()) {
            for (Object p : callable.get().getParameters()) {
                if (((Parameter) p).getNameAsString().equals(name)) {
                    return Optional.of(((Parameter) p).getType());
                }
            }
            for (VariableDeclarator v : callable.get().findAll(VariableDeclarator.class)) {
                if (v.getNameAsString().equals(name)) {
                    return Optional.of(v.getType());
                }
            }
        }
        for (TypeDeclaration<?> t : enclosingTypes(at)) {
            Optional<FieldDeclaration> field = t.getFieldByName(name);
            if (field.isPresent()) {
                return field.get().getVariables().stream().filter(v -> v.getNameAsString().equals(name))
                        .findFirst().map(VariableDeclarator::getType);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- типы проекта для SymbolSolver

    /** Типы проекта из памяти (файлы на диск не пишем). */
    private static final class ProjectTypeSolver implements TypeSolver {
        private final Map<String, TypeDeclaration<?>> types;
        private TypeSolver parent;

        ProjectTypeSolver(Map<String, TypeDeclaration<?>> types) {
            this.types = types;
        }

        @Override
        public TypeSolver getParent() {
            return parent;
        }

        @Override
        public void setParent(TypeSolver parent) {
            this.parent = parent;
        }

        @Override
        public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
            TypeDeclaration<?> t = types.get(name);
            if (t == null) {
                return SymbolReference.unsolved();
            }
            return SymbolReference.solved(JavaParserFacade.get(getRoot()).getTypeDeclaration(t));
        }
    }
}
