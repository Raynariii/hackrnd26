package io.blueprints.analysis.decompose;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import io.blueprints.analysis.FixNotApplicableException;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Применение декомпозиции (v1, G7): переносит <b>static-методы</b> и <b>static-поля</b> монолита
 * в новые классы {@code Parser / Analyzer / Optimizer} по тому же разбиению, что и {@link MonolithSplitter}.
 *
 * <p>Почему только static: перенос instance-методов требует переносить состояние и менять создание
 * объекта — это уже не механическая правка. Поэтому v1 оставляет {@code main}, конструкторы и
 * instance-методы в исходном классе. Статическое поле переносится, только если его читают/пишут
 * исключительно методы одной переносимой группы; общее для групп поле остаётся в монолите, а
 * методы, которые его трогают, не переносятся — иначе код перестал бы компилироваться.
 *
 * <p>Вызовы между классами квалифицируются ({@code Parser.readLine(...)}), ссылки на оставшиеся
 * вложенные типы — тоже ({@code Monolith.Order}); {@code private} у оставшихся методов и полей
 * снимается, чтобы перенесённый код имел доступ. Вложенные static-типы v1 не выносит отдельными
 * файлами — только квалифицирует ссылки на них.
 *
 * <p>Результат проверяется повторным разбором; компиляцию и дифф-тесты поведения делает quality gate
 * роли 4 — этот класс меняет только код и ничего не запускает.
 */
public final class MonolithApplier {

    /** Результат применения: новые исходники, пояснения и краткое описание для ответа API. */
    public record Applied(List<SourceFile> files, List<String> notes, String description) {
    }

    /** Что именно переносим в один новый класс. */
    private record GroupMove(String className, List<MethodDeclaration> methods, List<FieldDeclaration> fields) {
    }

    private final MonolithSplitter splitter = new MonolithSplitter();
    private final JavaProjectParser parser = new JavaProjectParser();
    private final CodeGenerator codeGenerator = new CodeGenerator();
    private static final DefaultPrettyPrinter PRETTY_PRINTER = new DefaultPrettyPrinter();

    /**
     * Применяет декомпозицию к копии исходников.
     *
     * @throws FixNotApplicableException если проект не разбирается, декомпозировать нечего или
     *                                   результат не разбирается (тогда ничего не возвращается)
     */
    public Applied apply(List<SourceFile> files) {
        ProjectModel model = parser.parse(files);
        if (model.hasErrors()) {
            throw new FixNotApplicableException("Проект разбирается с ошибками: декомпозиция не применяется");
        }
        List<MonolithSplitter.Plan> plans = splitter.plan(model);
        if (plans.isEmpty()) {
            throw new FixNotApplicableException("Декомпозировать нечего: нет god-класса с двумя группами методов");
        }
        // Один класс за шаг: имена Parser/Analyzer фиксированы, два таких класса в одном пакете столкнутся.
        MonolithSplitter.Plan plan = plans.stream()
                .max(Comparator.comparingInt(candidate -> candidate.owner().getMethods().size()))
                .orElseThrow();
        List<String> notes = new ArrayList<>();
        Map<String, CompilationUnit> units = new LinkedHashMap<>(model.units());
        String description = applyPlan(plan, model, units, notes);

        Set<String> created = new LinkedHashSet<>(units.keySet());
        created.removeAll(model.units().keySet());
        // Новые файлы печатаем pretty-print: у них нет сохранённого форматирования, а LPP на свежем CU не настроен.
        Map<String, CompilationUnit> existing = new LinkedHashMap<>(units);
        existing.keySet().removeAll(created);
        CodeGenerator.Printed printed = codeGenerator.printReport(new ProjectModel(existing, model.problems()));
        List<SourceFile> printedFiles = new ArrayList<>(printed.files());
        for (String path : created) {
            printedFiles.add(new SourceFile(path, PRETTY_PRINTER.print(units.get(path))));
        }
        ProjectModel check = parser.parse(printedFiles);
        if (check.hasErrors()) {
            throw new FixNotApplicableException("После декомпозиции код не разбирается — изменение отменено");
        }
        printed.reformatted().stream()
                .filter(path -> !created.contains(path))
                .forEach(path -> notes.add("Форматирование " + path + " перепечатано pretty-print"));
        return new Applied(List.copyOf(printedFiles), List.copyOf(notes), description);
    }

    private String applyPlan(MonolithSplitter.Plan plan, ProjectModel model,
                             Map<String, CompilationUnit> units, List<String> notes) {
        ClassOrInterfaceDeclaration owner = plan.owner();
        CompilationUnit ownerUnit = owner.findCompilationUnit()
                .orElseThrow(() -> new FixNotApplicableException("Класс вне compilation unit: декомпозиция невозможна"));
        String ownerName = owner.getNameAsString();
        String ownerFile = model.fileOf(owner).orElse(ownerName + ".java");
        int ownerMethodCount = owner.getMethods().size();

        Map<String, FieldDeclaration> singleFields = singleVariableFields(owner);
        List<String> nestedTypeNames = owner.getMembers().stream()
                .filter(TypeDeclaration.class::isInstance)
                .map(member -> ((TypeDeclaration<?>) member).getNameAsString())
                .toList();
        Map<String, List<MethodDeclaration>> byName = owner.getMethods().stream()
                .collect(Collectors.groupingBy(MethodDeclaration::getNameAsString));

        Set<MethodDeclaration> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<MethodDeclaration, Set<String>> methodFields = new IdentityHashMap<>();
        for (MethodDeclaration method : owner.getMethods()) {
            if (!method.isStatic() || "main".equals(method.getNameAsString())
                    || byName.get(method.getNameAsString()).size() != 1) {
                continue;
            }
            candidates.add(method);
            methodFields.put(method, referencedFields(method, singleFields.keySet()));
        }
        FieldUsage usage = fieldUsage(owner, ownerName, singleFields.keySet());

        List<GroupMove> groups = new ArrayList<>();
        Set<String> movedFieldNames = new LinkedHashSet<>();
        for (MonolithSplitter.PlannedGroup group : plan.groups()) {
            List<MethodDeclaration> groupCandidates = group.methods().stream().filter(candidates::contains).toList();
            if (groupCandidates.size() < 2) {
                continue;
            }
            Set<String> fields = new LinkedHashSet<>();
            for (MethodDeclaration method : groupCandidates) {
                fields.addAll(methodFields.get(method));
            }
            fields.removeIf(field -> usage.external().contains(field)
                    || !groupCandidates.containsAll(usage.users().getOrDefault(field, Set.of())));

            Set<MethodDeclaration> movable = groupCandidates.stream()
                    .filter(method -> fields.containsAll(methodFields.get(method)))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String field : List.copyOf(fields)) {
                    if (!movable.containsAll(usage.users().getOrDefault(field, Set.of()))) {
                        fields.remove(field);
                        movable.removeIf(method -> methodFields.get(method).contains(field));
                        changed = true;
                    }
                }
            }
            if (movable.size() < 2) {
                continue;
            }
            List<FieldDeclaration> fieldDeclarations = fields.stream().map(singleFields::get).toList();
            groups.add(new GroupMove(group.className(), List.copyOf(movable), fieldDeclarations));
            movedFieldNames.addAll(fields);
        }
        if (groups.isEmpty()) {
            throw new FixNotApplicableException("Декомпозиция v1 переносит только static-методы без общего состояния: "
                    + "в " + ownerName + " нет ни одной группы из 2+ таких методов");
        }

        // Перенесённый код обращается к оставшимся членам и вложенным типам — снимаем private.
        owner.getMethods().forEach(method -> method.getModifiers()
                .removeIf(modifier -> modifier.getKeyword() == Modifier.Keyword.PRIVATE));
        owner.getFields().forEach(field -> field.getModifiers()
                .removeIf(modifier -> modifier.getKeyword() == Modifier.Keyword.PRIVATE));
        owner.getMembers().stream()
                .filter(TypeDeclaration.class::isInstance)
                .map(member -> (TypeDeclaration<?>) member)
                .forEach(type -> type.getModifiers()
                        .removeIf(modifier -> modifier.getKeyword() == Modifier.Keyword.PRIVATE));

        Map<String, String> movedTo = new LinkedHashMap<>();
        groups.forEach(group -> group.methods()
                .forEach(method -> movedTo.put(method.getNameAsString(), group.className())));
        Set<String> staying = owner.getMethods().stream()
                .map(MethodDeclaration::getNameAsString)
                .filter(name -> !movedTo.containsKey(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<MethodDeclaration, String> callerTarget = new IdentityHashMap<>();
        owner.getMethods().forEach(method ->
                callerTarget.put(method, movedTo.getOrDefault(method.getNameAsString(), ownerName)));
        for (MethodDeclaration method : owner.getMethods()) {
            String from = callerTarget.get(method);
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                qualifyCall(call, from, ownerName, movedTo, staying);
            }
        }
        for (GroupMove group : groups) {
            for (MethodDeclaration method : group.methods()) {
                qualifyTypeReferences(method, ownerName, nestedTypeNames, movedFieldNames);
            }
            for (FieldDeclaration field : group.fields()) {
                qualifyTypeReferences(field, ownerName, nestedTypeNames, movedFieldNames);
                qualifyCalls(field, group.className(), ownerName, movedTo, staying);
            }
        }

        for (GroupMove group : groups) {
            ClassOrInterfaceDeclaration created = new ClassOrInterfaceDeclaration(new NodeList<>(), false, group.className());
            created.addModifier(Modifier.Keyword.FINAL);
            for (FieldDeclaration field : group.fields()) {
                field.remove();
                created.addMember(field);
            }
            for (MethodDeclaration method : group.methods()) {
                method.remove();
                created.addMember(method);
            }
            CompilationUnit unit = new CompilationUnit();
            ownerUnit.getPackageDeclaration().ifPresent(declaration -> unit.setPackageDeclaration(declaration.clone()));
            ownerUnit.getImports().forEach(importDeclaration -> unit.addImport(importDeclaration.clone()));
            unit.addType(created);
            units.put(filePath(ownerFile, group.className()), unit);
            notes.add(group.className() + ": методы " + String.join(", ", group.methods().stream()
                    .map(MethodDeclaration::getNameAsString).toList())
                    + (group.fields().isEmpty() ? "" : "; поля " + String.join(", ", group.fields().stream()
                    .map(field -> field.getVariable(0).getNameAsString()).toList())));
        }

        int fieldCount = groups.stream().mapToInt(group -> group.fields().size()).sum();
        int methodCount = movedTo.size();
        String description = "Перенесено " + methodCount + " static-" + plural(methodCount, "метод", "метода", "методов")
                + (fieldCount == 0 ? "" : " и " + fieldCount + " static-" + plural(fieldCount, "поле", "поля", "полей"))
                + " в " + groups.size() + " " + plural(groups.size(), "класс", "класса", "классов") + " ("
                + groups.stream().map(GroupMove::className).collect(Collectors.joining(", "))
                + "). Точка входа, общие поля и instance-методы остались в " + ownerName + ".";
        notes.add("Выбран класс " + ownerName + " (" + ownerMethodCount + " методов) — один за шаг");
        return description;
    }

    /** Русская форма числительного: 1 метод, 2 метода, 5 методов. */
    private static String plural(int count, String one, String few, String many) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return few;
        }
        return many;
    }

    /** Поля, у которых объявлена ровно одна переменная: только такие можно переносить целиком. */
    private static Map<String, FieldDeclaration> singleVariableFields(ClassOrInterfaceDeclaration owner) {
        Map<String, FieldDeclaration> fields = new LinkedHashMap<>();
        for (FieldDeclaration field : owner.getFields()) {
            if (field.getVariables().size() == 1) {
                fields.put(field.getVariable(0).getNameAsString(), field);
            }
        }
        return fields;
    }

    /** Кто читает/пишет поле: методы класса-владельца; ссылки вне методов — «внешние» (поле не переносим). */
    private record FieldUsage(Map<String, Set<MethodDeclaration>> users, Set<String> external) {
    }

    private static FieldUsage fieldUsage(ClassOrInterfaceDeclaration owner, String ownerName, Set<String> fieldNames) {
        Map<String, Set<MethodDeclaration>> users = new LinkedHashMap<>();
        fieldNames.forEach(name -> users.put(name, new LinkedHashSet<>()));
        Set<String> external = new LinkedHashSet<>();
        for (NameExpr name : owner.findAll(NameExpr.class)) {
            String field = name.getNameAsString();
            Set<MethodDeclaration> fieldUsers = users.get(field);
            if (fieldUsers == null) {
                continue;
            }
            MethodDeclaration method = name.findAncestor(MethodDeclaration.class).orElse(null);
            if (method != null && method.getParentNode().orElse(null) == owner) {
                fieldUsers.add(method);
            } else {
                external.add(field);
            }
        }
        for (FieldAccessExpr access : owner.findAll(FieldAccessExpr.class)) {
            if (!ownerName.equals(access.getScope().toString())) {
                continue;
            }
            String field = access.getNameAsString();
            Set<MethodDeclaration> fieldUsers = users.get(field);
            if (fieldUsers == null) {
                continue;
            }
            MethodDeclaration method = access.findAncestor(MethodDeclaration.class).orElse(null);
            if (method != null && method.getParentNode().orElse(null) == owner) {
                fieldUsers.add(method);
            } else {
                external.add(field);
            }
        }
        return new FieldUsage(users, external);
    }

    /**
     * Квалифицирует вызов, если он уходит в другой класс: {@code readLine(x)} в {@code main} →
     * {@code Parser.readLine(x)}; вызовы внутри переносимого класса на его же методы остаются без scope.
     */
    private static void qualifyCall(MethodCallExpr call, String from, String ownerName,
                                    Map<String, String> movedTo, Set<String> staying) {
        String name = call.getNameAsString();
        String target = movedTo.get(name);
        if (target == null) {
            if (!staying.contains(name)) {
                return;
            }
            target = ownerName;
        }
        String scope = call.getScope().map(Object::toString).orElse(null);
        boolean unqualified = scope == null || ownerName.equals(scope) || "this".equals(scope);
        if (unqualified && !target.equals(from)) {
            call.setScope(new NameExpr(target));
        }
    }

    private static void qualifyCalls(com.github.javaparser.ast.Node node, String from, String ownerName,
                                     Map<String, String> movedTo, Set<String> staying) {
        for (MethodCallExpr call : node.findAll(MethodCallExpr.class)) {
            qualifyCall(call, from, ownerName, movedTo, staying);
        }
    }

    /**
     * При переносе ссылки на оставшийся вложенный тип и на перенесённое статическое поле нужно
     * квалифицировать: {@code Order} → {@code Monolith.Order}, {@code Monolith.parsed} → {@code parsed}.
     */
    private static void qualifyTypeReferences(com.github.javaparser.ast.Node node, String ownerName,
                                              List<String> nestedTypeNames, Set<String> movedFields) {
        for (ClassOrInterfaceType type : node.findAll(ClassOrInterfaceType.class)) {
            if (type.getScope().isEmpty() && nestedTypeNames.contains(type.getNameAsString())) {
                type.setScope(new ClassOrInterfaceType(ownerName));
            }
        }
        for (FieldAccessExpr access : node.findAll(FieldAccessExpr.class)) {
            if (ownerName.equals(access.getScope().toString()) && movedFields.contains(access.getNameAsString())) {
                access.replace(new NameExpr(access.getNameAsString()));
            }
        }
    }

    /** Обращается ли метод к полям класса; переменные и параметры с тем же именем не считаются полем. */
    private static Set<String> referencedFields(MethodDeclaration method, Set<String> fieldNames) {
        Set<String> referenced = new LinkedHashSet<>();
        if (fieldNames.isEmpty()) {
            return referenced;
        }
        Set<String> locals = new HashSet<>();
        method.getParameters().forEach(parameter -> locals.add(parameter.getNameAsString()));
        method.findAll(VariableDeclarator.class).forEach(declarator -> locals.add(declarator.getNameAsString()));
        method.findAll(LambdaExpr.class).forEach(lambda ->
                lambda.getParameters().forEach(parameter -> locals.add(parameter.getNameAsString())));
        method.findAll(NameExpr.class).forEach(name -> {
            if (fieldNames.contains(name.getNameAsString()) && !locals.contains(name.getNameAsString())) {
                referenced.add(name.getNameAsString());
            }
        });
        return referenced;
    }

    /** Файл нового класса — рядом с исходным: {@code src/Main.java} → {@code src/Parser.java}. */
    private static String filePath(String ownerFile, String className) {
        String normalized = ownerFile.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String directory = slash >= 0 ? normalized.substring(0, slash + 1) : "";
        return directory + className + ".java";
    }
}
