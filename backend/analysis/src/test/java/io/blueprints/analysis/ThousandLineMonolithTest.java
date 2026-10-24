package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Кейс Axenix — монолит около 1000 строк. Проверяем, что анализ такого размера укладывается
 * в разумное время и по-прежнему находит дефекты: это защита от случайной квадратичной
 * сложности внутри самого анализатора (например, повторного обхода AST в правиле).
 */
class ThousandLineMonolithTest {

    private static final int TARGET_LINES = 1000;

    @Test
    void analyzesThousandLineMonolithQuickly() {
        String source = monolith();
        assertThat(source.lines().count()).isGreaterThanOrEqualTo(TARGET_LINES - 50);

        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Monolith.java", source)));
        assertThat(model.hasErrors()).isFalse();

        long started = System.nanoTime();
        AnalysisReport report = Analyzer.withDefaultRules().analyze(model);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThan(5_000);
        assertThat(report.metrics().size()).isGreaterThan(60);
        assertThat(report.findings()).extracting(Finding::rule)
                .contains("collection-lookup-in-loop", "god-class", "duplicated-code");
        assertThat(report.findings()).hasSizeGreaterThan(65);
        assertThat(report.findings()).allSatisfy(finding -> assertThat(finding.nodeIds()).isNotEmpty());
    }

    /**
     * Монолит из методов с заложенными дефектами: {@code contains} по списку внутри цикла,
     * одинаковые тела методов и god-class.
     */
    private static String monolith() {
        StringBuilder source = new StringBuilder();
        source.append("import java.util.ArrayList;\n")
                .append("import java.util.List;\n\n")
                .append("public class Monolith {\n")
                .append("    private final List<String> items = new ArrayList<>();\n\n");
        int index = 0;
        while (countLines(source) < TARGET_LINES - 12) {
            source.append("    int process").append(index).append("(int seed) {\n")
                    .append("        int total = seed;\n")
                    .append("        int count = 0;\n")
                    .append("        for (String item : items) {\n")
                    .append("            if (items.contains(item)) {\n")
                    .append("                total += item.length();\n")
                    .append("                count++;\n")
                    .append("            }\n")
                    .append("        }\n")
                    .append("        total = total * 2;\n")
                    .append("        System.out.println(count);\n")
                    .append("        return total;\n")
                    .append("    }\n\n");
            index++;
        }
        source.append("}\n");
        return source.toString();
    }

    private static long countLines(CharSequence source) {
        long lines = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
