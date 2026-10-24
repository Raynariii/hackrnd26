package io.blueprints.analysis.fixes;

import com.github.javaparser.ast.Node;
import io.blueprints.analysis.Ast;
import io.blueprints.analysis.Finding;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Возвращает отступ оператору, который {@link com.github.javaparser.printer.lexicalpreservation
 * .LexicalPreservingPrinter} напечатал на уровень выше.
 *
 * <p>LPP сохраняет форматирование существующего кода, но новый оператор, вставленный в начало блока,
 * печатает с отступом родителя — в диффе это выглядит как «съехавший» код (известное ограничение
 * из {@code STATUS.md}). Правка кода от этого не страдает, поэтому чиним на печати: берём отступ
 * у оператора-контейнера в <em>исходном</em> файле и выравниваем по нему вставленную строку и сам
 * контейнер, если LPP сдвинул и его.
 *
 * <p>Если вставки не было (например, правка внутри существующего условия), {@link #of} возвращает
 * {@link Optional#empty()} и печать остаётся нетронутой.
 */
public final class Reindent {

    private final String path;
    private final String firstLine;
    private final String indent;

    private Reindent(String path, String firstLine, String indent) {
        this.path = path;
        this.firstLine = firstLine;
        this.indent = indent;
    }

    /**
     * Отступ, который должен быть у оператора, в начало которого вставят строку: для находки берём
     * ближайший цикл-предок и его место в исходном файле.
     */
    public static Optional<Reindent> of(ProjectModel model, Finding finding, List<SourceFile> original) {
        if (finding.nodeIds().isEmpty()) {
            return Optional.empty();
        }
        Node anchor = model.nodeById(finding.nodeIds().get(0).substring(1)).orElse(null);
        if (anchor == null) {
            return Optional.empty();
        }
        Node loop = Ast.enclosingLoops(anchor).stream().findFirst().orElse(null);
        if (loop == null) {
            return Optional.empty();
        }
        String path = model.fileOf(loop).orElse(null);
        if (path == null) {
            return Optional.empty();
        }
        int line = Ast.startLine(loop);
        SourceFile source = original.stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElse(null);
        if (source == null || line <= 0) {
            return Optional.empty();
        }
        String[] lines = source.content().split("\n", -1);
        if (line > lines.length) {
            return Optional.empty();
        }
        String text = lines[line - 1];
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String indent = text.substring(0, text.length() - text.stripLeading().length());
        return Optional.of(new Reindent(path, trimmed, indent));
    }

    /** Выравнивает вставленную строку и сдвинутый контейнер по отступу исходника. */
    public List<SourceFile> apply(List<SourceFile> printed) {
        List<SourceFile> result = new ArrayList<>(printed.size());
        for (SourceFile file : printed) {
            if (!file.path().equals(path)) {
                result.add(file);
                continue;
            }
            String[] lines = file.content().split("\n", -1);
            for (int index = 0; index < lines.length; index++) {
                if (!lines[index].strip().equals(firstLine)) {
                    continue;
                }
                lines[index] = indent + firstLine;
                int previous = index - 1;
                while (previous >= 0 && lines[previous].isBlank()) {
                    previous--;
                }
                if (previous >= 0 && indentOf(lines[previous]).length() < indent.length()) {
                    lines[previous] = indent + lines[previous].strip();
                }
            }
            result.add(new SourceFile(file.path(), String.join("\n", lines)));
        }
        return result;
    }

    private static String indentOf(String line) {
        return line.substring(0, line.length() - line.stripLeading().length());
    }
}
