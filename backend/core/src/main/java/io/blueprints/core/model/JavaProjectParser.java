package io.blueprints.core.model;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Разбор исходников в {@link ProjectModel}. Форматирование сохраняется через LexicalPreservingPrinter. */
public final class JavaProjectParser {

    private final ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    public ProjectModel parse(List<SourceFile> files) {
        Map<String, CompilationUnit> units = new LinkedHashMap<>();
        List<ParseProblem> problems = new ArrayList<>();
        JavaParser parser = new JavaParser(configuration);
        for (SourceFile file : files) {
            ParseResult<CompilationUnit> result = parser.parse(file.content());
            result.getProblems().forEach(p -> problems.add(new ParseProblem(
                    file.path(),
                    p.getLocation().flatMap(l -> l.getBegin().getRange()).map(r -> r.begin.line).orElse(null),
                    p.getLocation().flatMap(l -> l.getBegin().getRange()).map(r -> r.begin.column).orElse(null),
                    p.getMessage())));
            result.getResult().ifPresent(cu -> {
                LexicalPreservingPrinter.setup(cu);
                units.put(file.path(), cu);
            });
        }
        return new ProjectModel(units, problems);
    }
}
