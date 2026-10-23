package io.blueprints.core.model;

import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.List;

/** AST → исходники. Неизменённые участки кода печатаются байт-в-байт как в оригинале. */
public final class CodeGenerator {

    public List<SourceFile> print(ProjectModel model) {
        return model.units().entrySet().stream()
                .map(e -> new SourceFile(e.getKey(), LexicalPreservingPrinter.print(e.getValue())))
                .toList();
    }
}
