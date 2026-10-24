package io.blueprints.analysis.decompose;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Алгоритм декомпозиции монолита: классифицирует методы god-класса по имени (camelCase-токены) и по
 * тому, кого метод зовёт, и раскладывает их по {@code Parser / Analyzer / Optimizer / Main}.
 *
 * <p>Внутренности подроли 2c: публичная точка входа для api — {@code Decomposer#propose(model)} и
 * {@code Decomposer#apply(files)}, {@code Decomposer} остаётся фасадом. Здесь — только классификация;
 * фактический перенос статических методов — {@link MonolithApplier}.
 */
public final class MonolithSplitter {

    /** Группа методов в предложении: имя класса, ответственность и что в него переносим. */
    public record Group(String className, String responsibility, List<String> members) {
    }

    /** Результат разбора: группы по классам и пояснения для панели «Декомпозиция». */
    public record Split(List<Group> groups, List<String> notes) {
    }

    /**
     * План применения для одного класса: владелец и группы его методов (те же, что в предложении).
     * Нужен {@code MonolithApplier}: предложение показывает сигнатуры, а применению нужны AST-узлы.
     */
    public record Plan(ClassOrInterfaceDeclaration owner, List<PlannedGroup> groups) {
    }

    /** Группа методов, которая станет отдельным классом при применении. */
    public record PlannedGroup(String className, String responsibility, List<MethodDeclaration> methods) {
    }

    /** Минимум методов в классе, чтобы вообще обсуждать декомпозицию. */
    private static final int MIN_METHODS = 6;
    /** Минимум методов в группе, чтобы выделять её в отдельный класс. */
    private static final int MIN_GROUP = 2;
    /** Сколько проходов label propagation делаем по графу зависимостей. */
    private static final int PROPAGATION_PASSES = 5;

    private static final Map<Responsibility, List<String>> KEYWORDS = keywords();

    /** {@link EnumMap} — обход в порядке объявления ответственностей, разбиение детерминировано. */
    private static Map<Responsibility, List<String>> keywords() {
        Map<Responsibility, List<String>> map = new EnumMap<>(Responsibility.class);
        map.put(Responsibility.PARSER, List.of("read", "parse", "load", "scan", "input", "token", "file", "line",
                "prompt", "next", "import", "extract"));
        map.put(Responsibility.ANALYZER, List.of("analyz", "valid", "check", "detect", "find", "count", "stat",
                "report", "comput", "calculat", "summar", "metric", "inspect", "verif", "evaluat"));
        map.put(Responsibility.OPTIMIZER, List.of("optim", "sort", "dedup", "merge", "cach", "index", "normaliz",
                "clean", "filter", "group", "aggregat", "compress", "reduce", "batch"));
        map.put(Responsibility.MAIN, List.of("main", "run", "start", "execut", "demo", "cli", "menu", "usage"));
        return Collections.unmodifiableMap(map);
    }

    private enum Responsibility {
        PARSER("Parser", "Разбор и чтение входных данных"),
        ANALYZER("Analyzer", "Проверки, подсчёты и отчёты по данным"),
        OPTIMIZER("Optimizer", "Сортировка, индексация и оптимизации"),
        MAIN("Main", "Точка входа и сценарий запуска"),
        UTILS("Utils", "Вспомогательные операции");

        private final String className;
        private final String description;

        Responsibility(String className, String description) {
            this.className = className;
            this.description = description;
        }
    }

    /** Предложение по всем god-классам проекта. */
    public Split split(ProjectModel model) {
        List<Group> classes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Plan plan : plan(model)) {
            List<Group> split = new ArrayList<>();
            for (PlannedGroup group : plan.groups()) {
                split.add(proposedClass(plan.owner(), group.className(), group.responsibility(), group.methods()));
            }
            classes.addAll(split);
            notes.add("Класс " + plan.owner().getNameAsString()
                    + " (" + model.fileOf(plan.owner()).orElse("?") + "): " + plan.owner().getMethods().size()
                    + " методов → " + split.size() + " класса");
            notes.add(cohesionNote(plan, plan.groups()));
        }
        return new Split(List.copyOf(classes), List.copyOf(notes));
    }

    /**
     * Метрика качества разбиения для жюри: сколько вызовов остаётся внутри групп, а сколько «разрезано»
     * между будущими классами. Чем меньше разрезанных зависимостей, тем чище декомпозиция — при 0 %
     * классы можно разрабатывать и тестировать независимо.
     */
    private static String cohesionNote(Plan plan, List<PlannedGroup> groups) {
        Map<String, Integer> groupOf = new HashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            for (MethodDeclaration method : groups.get(index).methods()) {
                groupOf.put(method.getNameAsString(), index);
            }
        }
        int internal = 0;
        int crossing = 0;
        for (int index = 0; index < groups.size(); index++) {
            for (MethodDeclaration method : groups.get(index).methods()) {
                for (String call : localCalls(method)) {
                    Integer target = groupOf.get(call);
                    if (target == null) {
                        continue;
                    }
                    if (target == index) {
                        internal++;
                    } else {
                        crossing++;
                    }
                }
            }
        }
        int total = internal + crossing;
        int percent = total == 0 ? 0 : (int) Math.round(100.0 * crossing / total);
        return "Сцепление «" + plan.owner().getNameAsString() + "»: вызовов внутри групп " + internal
                + ", между группами " + crossing + " (" + percent + "% зависимостей разрезано)";
    }

    /**
     * План применения: для каждого god-класса — группы методов с AST-узлами. Порядок групп
     * как в предложении (Parser, Analyzer, Optimizer, Main, Utils), чтобы имена классов совпадали.
     */
    public List<Plan> plan(ProjectModel model) {
        List<Plan> plans = new ArrayList<>();
        model.units().forEach((file, cu) -> {
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface()) {
                    continue;
                }
                List<MethodDeclaration> methods = type.getMethods();
                if (methods.size() < MIN_METHODS) {
                    continue;
                }
                Map<Responsibility, List<MethodDeclaration>> groups = group(type, methods);
                List<PlannedGroup> split = new ArrayList<>();
                for (Map.Entry<Responsibility, List<MethodDeclaration>> group : groups.entrySet()) {
                    if (group.getValue().size() < MIN_GROUP) {
                        continue;
                    }
                    split.add(new PlannedGroup(group.getKey().className, group.getKey().description,
                            List.copyOf(group.getValue())));
                }
                if (split.size() < 2) {
                    continue;
                }
                plans.add(new Plan(type, List.copyOf(split)));
            }
        });
        return List.copyOf(plans);
    }

    private Map<Responsibility, List<MethodDeclaration>> group(ClassOrInterfaceDeclaration owner,
                                                               List<MethodDeclaration> methods) {
        Map<Responsibility, List<MethodDeclaration>> groups = new LinkedHashMap<>();
        for (Responsibility responsibility : Responsibility.values()) {
            groups.put(responsibility, new ArrayList<>());
        }
        // Имена дают только «семена»: метод с говорящим именем задаёт метку кластера.
        Map<String, Responsibility> labels = new LinkedHashMap<>();
        for (MethodDeclaration method : methods) {
            labels.put(method.getNameAsString(), classifyByName(method));
        }
        // Дальше метки расползаются по графу зависимостей (вызовы в обе стороны + общие поля):
        // безымянные помощники попадают к тем, кто их зовёт, а не наугад.
        Map<String, Set<String>> adjacency = adjacency(owner, methods);
        for (int pass = 0; pass < PROPAGATION_PASSES; pass++) {
            boolean changed = false;
            for (MethodDeclaration method : methods) {
                String name = method.getNameAsString();
                if (labels.get(name) != Responsibility.UTILS) {
                    continue;
                }
                Responsibility propagated = vote(adjacency.getOrDefault(name, Set.of()), labels);
                if (propagated != Responsibility.UTILS) {
                    labels.put(name, propagated);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        for (MethodDeclaration method : methods) {
            groups.get(labels.get(method.getNameAsString())).add(method);
        }
        groups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return groups;
    }

    /**
     * Ненаправленный граф зависимостей методов класса: вызов (в любую сторону) и общее поле.
     * По нему распространяются метки — это и есть label propagation из PLAN.md §4.
     */
    private static Map<String, Set<String>> adjacency(ClassOrInterfaceDeclaration owner,
                                                     List<MethodDeclaration> methods) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        methods.forEach(method -> names.add(method.getNameAsString()));
        for (MethodDeclaration method : methods) {
            String name = method.getNameAsString();
            adjacency.putIfAbsent(name, new LinkedHashSet<>());
            for (String called : localCalls(method)) {
                if (!names.contains(called) || called.equals(name)) {
                    continue;
                }
                adjacency.get(name).add(called);
                adjacency.computeIfAbsent(called, ignored -> new LinkedHashSet<>()).add(name);
            }
        }
        // Общее <em>поле класса</em> — тоже зависимость (локальные переменные и параметры не в счёт,
        // иначе через `order` соединились бы все методы подряд).
        Set<String> fields = new LinkedHashSet<>();
        owner.getFields().forEach(field -> field.getVariables()
                .forEach(declarator -> fields.add(declarator.getNameAsString())));
        Map<String, Set<String>> byField = new LinkedHashMap<>();
        for (MethodDeclaration method : methods) {
            for (NameExpr read : method.findAll(NameExpr.class)) {
                if (!fields.contains(read.getNameAsString())) {
                    continue;
                }
                byField.computeIfAbsent(read.getNameAsString(), ignored -> new LinkedHashSet<>())
                        .add(method.getNameAsString());
            }
        }
        for (Set<String> sharing : byField.values()) {
            if (sharing.size() < 2) {
                continue;
            }
            for (String left : sharing) {
                for (String right : sharing) {
                    if (!left.equals(right) && names.contains(right)) {
                        adjacency.computeIfAbsent(left, ignored -> new LinkedHashSet<>()).add(right);
                    }
                }
            }
        }
        return adjacency;
    }

    /**
     * Метка, которую поддерживает большинство соседей; {@code UTILS}, если поддержки нет.
     * При равенстве голосов не отдаём метод точке входа: {@code Main} должен оставаться сценарием,
     * а не собирать вокруг себя всю логику.
     */
    private static Responsibility vote(Set<String> neighbours, Map<String, Responsibility> labels) {
        Map<Responsibility, Integer> votes = new LinkedHashMap<>();
        for (String neighbour : neighbours) {
            Responsibility label = labels.get(neighbour);
            if (label != null && label != Responsibility.UTILS) {
                votes.merge(label, 1, Integer::sum);
            }
        }
        return votes.entrySet().stream()
                .min((left, right) -> {
                    int byVotes = Integer.compare(right.getValue(), left.getValue());
                    if (byVotes != 0) {
                        return byVotes;
                    }
                    boolean leftMain = left.getKey() == Responsibility.MAIN;
                    boolean rightMain = right.getKey() == Responsibility.MAIN;
                    if (leftMain != rightMain) {
                        return leftMain ? 1 : -1;
                    }
                    return Integer.compare(left.getKey().ordinal(), right.getKey().ordinal());
                })
                .map(Map.Entry::getKey)
                .orElse(Responsibility.UTILS);
    }

    private static Responsibility classifyByName(MethodDeclaration method) {
        if (method.isStatic() && "main".equals(method.getNameAsString())) {
            return Responsibility.MAIN;
        }
        List<String> words = words(method.getNameAsString());
        for (Map.Entry<Responsibility, List<String>> entry : KEYWORDS.entrySet()) {
            for (String word : words) {
                for (String keyword : entry.getValue()) {
                    if (word.startsWith(keyword)) {
                        return entry.getKey();
                    }
                }
            }
        }
        return Responsibility.UTILS;
    }

    /** Имена методов, которые вызывает метод (только свои, без разыменования). */
    private static Set<String> localCalls(MethodDeclaration method) {
        Set<String> calls = new LinkedHashSet<>();
        method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call ->
                call.getScope().ifPresentOrElse(
                        scope -> {
                            // this.foo() / ClassName.foo() — считаем локальным вызовом
                        },
                        () -> calls.add(call.getNameAsString())));
        return calls;
    }

    private static Group proposedClass(ClassOrInterfaceDeclaration owner, String className, String responsibility,
                                       List<MethodDeclaration> methods) {
        List<String> members = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            members.add(signature(method));
        }
        members.addAll(fieldsUsedBy(owner, methods));
        return new Group(className, responsibility, List.copyOf(members));
    }

    private static String signature(MethodDeclaration method) {
        String parameters = method.getParameters().stream()
                .map(parameter -> simpleType(parameter.getType().asString()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return method.getNameAsString() + "(" + parameters + ")";
    }

    /** Статические поля, которые читают методы группы: без них перенос не скомпилируется. */
    private static List<String> fieldsUsedBy(ClassOrInterfaceDeclaration owner, List<MethodDeclaration> methods) {
        Set<String> used = new LinkedHashSet<>();
        for (FieldDeclaration field : owner.getFields()) {
            for (VariableDeclarator declarator : field.getVariables()) {
                String name = declarator.getNameAsString();
                boolean referenced = methods.stream()
                        .anyMatch(method -> method.findAll(NameExpr.class).stream()
                                .anyMatch(expression -> expression.getNameAsString().equals(name)));
                if (referenced) {
                    used.add("field " + name);
                }
            }
        }
        return List.copyOf(used);
    }

    private static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String part : name.split("(?=[A-Z])|_")) {
            if (!part.isBlank()) {
                words.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    private static String simpleType(String type) {
        int generic = type.indexOf('<');
        String name = generic >= 0 ? type.substring(0, generic) : type;
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
