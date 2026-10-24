package io.blueprints.core.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Разобранный проект — источник истины. Граф строится из него, операции над графом меняют его AST.
 * Каждому AST-узлу присваивается id, по которому граф ссылается на код.
 */
public final class ProjectModel {

    private final Map<String, CompilationUnit> units;
    private final List<ParseProblem> problems;
    private final Map<Node, String> idsByNode = new IdentityHashMap<>();
    private final Map<String, Node> nodesById = new LinkedHashMap<>();

    public ProjectModel(Map<String, CompilationUnit> units, List<ParseProblem> problems) {
        this.units = units;
        this.problems = problems;
        reindex();
    }

    /** Путь файла → CompilationUnit, в порядке загрузки. */
    public Map<String, CompilationUnit> units() {
        return Collections.unmodifiableMap(units);
    }

    public List<ParseProblem> problems() {
        return problems;
    }

    public boolean hasErrors() {
        return !problems.isEmpty();
    }

    public String idOf(Node node) {
        return idsByNode.get(node);
    }

    public Optional<Node> nodeById(String astId) {
        return Optional.ofNullable(nodesById.get(astId));
    }

    public Optional<String> fileOf(Node node) {
        CompilationUnit cu = node.findCompilationUnit().orElse(null);
        return units.entrySet().stream().filter(e -> e.getValue() == cu).map(Map.Entry::getKey).findFirst();
    }

    /**
     * Заменить файл заново разобранным AST (операции графа правят текст по диапазонам узлов и разбирают
     * результат). Id пересчитываются.
     */
    public void replaceUnit(String path, CompilationUnit cu) {
        if (!units.containsKey(path)) {
            throw new IllegalArgumentException("Нет файла " + path);
        }
        units.put(path, cu);
        reindex();
    }

    /** Пересчитать id после изменения AST. Id детерминированы: порядок обхода файлов и узлов. */
    public void reindex() {
        idsByNode.clear();
        nodesById.clear();
        int counter = 0;
        for (CompilationUnit cu : units.values()) {
            for (Node node : cu.findAll(Node.class)) {
                String id = "a" + (++counter);
                idsByNode.put(node, id);
                nodesById.put(id, node);
            }
        }
    }
}
