package io.blueprints.analysis.decompose;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import io.blueprints.core.model.ProjectModel;

import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>Внутренности подроли 2c: публичная точка входа для api — {@code Decomposer#propose(model)},
 * {@code Decomposer} остаётся фасадом. v1 предлагает перенос, ничего не перемещая: применение —
 * отдельный шаг после quality gate (роль 4).
 */
public final class MonolithSplitter {

    /** Группа методов в предложении: имя класса, ответственность и что в него переносим. */
    public record Group(String className, String responsibility, List<String> members) {
    }

    /** Результат разбора: группы по классам и пояснения для панели «Декомпозиция». */
    public record Split(List<Group> groups, List<String> notes) {
    }

    /** Минимум методов в классе, чтобы вообще обсуждать декомпозицию. */
    private static final int MIN_METHODS = 6;
    /** Минимум методов в группе, чтобы выделять её в отдельный класс. */
    private static final int MIN_GROUP = 2;

    private static final Map<Responsibility, List<String>> KEYWORDS = Map.of(
            Responsibility.PARSER, List.of("read", "parse", "load", "scan", "input", "token", "file", "line",
                    "prompt", "next", "import", "extract"),
            Responsibility.ANALYZER, List.of("analyz", "valid", "check", "detect", "find", "count", "stat",
                    "report", "comput", "calculat", "summar", "metric", "inspect", "verif", "evaluat"),
            Responsibility.OPTIMIZER, List.of("optim", "sort", "dedup", "merge", "cach", "index", "normaliz",
                    "clean", "filter", "group", "aggregat", "compress", "reduce", "batch"),
            Responsibility.MAIN, List.of("main", "run", "start", "execut", "demo", "cli", "menu", "usage"));

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
        model.units().forEach((file, cu) -> {
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface()) {
                    continue;
                }
                List<MethodDeclaration> methods = type.getMethods();
                if (methods.size() < MIN_METHODS) {
                    continue;
                }
                Map<Responsibility, List<MethodDeclaration>> groups = group(methods);
                List<Group> split = new ArrayList<>();
                for (Map.Entry<Responsibility, List<MethodDeclaration>> group : groups.entrySet()) {
                    if (group.getValue().size() < MIN_GROUP) {
                        continue;
                    }
                    split.add(proposedClass(type, group.getKey(), group.getValue()));
                }
                if (split.size() < 2) {
                    continue;
                }
                split.sort(order());
                classes.addAll(split);
                notes.add("Класс " + type.getNameAsString() + " (" + file + "): " + methods.size()
                        + " методов → " + split.size() + " класса");
            }
        });
        return new Split(List.copyOf(classes), List.copyOf(notes));
    }

    private Map<Responsibility, List<MethodDeclaration>> group(List<MethodDeclaration> methods) {
        Map<Responsibility, List<MethodDeclaration>> groups = new LinkedHashMap<>();
        for (Responsibility responsibility : Responsibility.values()) {
            groups.put(responsibility, new ArrayList<>());
        }
        Map<String, Responsibility> byName = new LinkedHashMap<>();
        for (MethodDeclaration method : methods) {
            Responsibility responsibility = classifyByName(method);
            byName.put(method.getNameAsString(), responsibility);
            groups.get(responsibility).add(method);
        }
        // Второй проход: методы без «говорящего» имени относим туда, куда зовут их соседи.
        for (MethodDeclaration method : methods) {
            if (byName.get(method.getNameAsString()) != Responsibility.UTILS) {
                continue;
            }
            Responsibility byCalls = classifyByCalls(method, byName);
            if (byCalls != Responsibility.UTILS) {
                groups.get(Responsibility.UTILS).remove(method);
                groups.get(byCalls).add(method);
            }
        }
        groups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return groups;
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

    private static Responsibility classifyByCalls(MethodDeclaration method, Map<String, Responsibility> byName) {
        Map<Responsibility, Integer> votes = new LinkedHashMap<>();
        for (String name : localCalls(method)) {
            Responsibility responsibility = byName.get(name);
            if (responsibility != null && responsibility != Responsibility.UTILS) {
                votes.merge(responsibility, 1, Integer::sum);
            }
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(Responsibility.UTILS);
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

    private static Group proposedClass(ClassOrInterfaceDeclaration owner, Responsibility responsibility,
                                       List<MethodDeclaration> methods) {
        List<String> members = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            members.add(signature(method));
        }
        members.addAll(fieldsUsedBy(owner, methods));
        return new Group(responsibility.className, responsibility.description, List.copyOf(members));
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

    /** Порядок групп в ответе: как в PLAN.md — Parser, Analyzer, Optimizer, Main, Utils. */
    static Comparator<Group> order() {
        return Comparator.comparingInt(group -> {
            for (Responsibility responsibility : Responsibility.values()) {
                if (responsibility.className.equals(group.className())) {
                    return responsibility.ordinal();
                }
            }
            return Responsibility.values().length;
        });
    }
}
