package io.blueprints.core.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.ArrayList;
import java.util.List;

/** AST → исходники. Неизменённые участки кода печатаются байт-в-байт как в оригинале. */
public final class CodeGenerator {

    /**
     * Результат печати. {@code reformatted} — файлы, на которых LexicalPreservingPrinter упал и которые напечатаны
     * через pretty-print: код и поведение те же, но форматирование файла потеряно (G6).
     */
    public record Printed(List<SourceFile> files, List<String> reformatted) {
    }

    public List<SourceFile> print(ProjectModel model) {
        return printReport(model).files();
    }

    public Printed printReport(ProjectModel model) {
        List<SourceFile> files = new ArrayList<>();
        List<String> reformatted = new ArrayList<>();
        model.units().forEach((path, cu) -> {
            String text;
            try {
                text = LexicalPreservingPrinter.print(cu);
            } catch (RuntimeException e) {
                text = prettyPrint(cu);
                reformatted.add(path);
            }
            files.add(new SourceFile(path, text));
        });
        return new Printed(List.copyOf(files), List.copyOf(reformatted));
    }

    private static String prettyPrint(CompilationUnit cu) {
        return new DefaultPrettyPrinter().print(cu);
    }
}
