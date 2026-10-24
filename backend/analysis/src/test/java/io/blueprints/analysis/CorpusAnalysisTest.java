package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Прогон анализатора по всему корпусу {@code corpus/} (роль 4 пополняет его монолитами).
 *
 * <p>Зеркало {@code CorpusRoundTripTest} из ядра: любой новый пример корпуса автоматически проверяется
 * на то, что анализ не падает, находки ссылаются на реальные строки и узлы, а метрики считаются.
 * Пока в корпусе только smoke-примеры, тест фиксирует базовую линию; с появлением монолитов
 * он сразу покажет, какие правила шумят на реальном коде.
 */
class CorpusAnalysisTest {

    private static final Path CORPUS = Path.of("..", "..", "corpus");

    @Test
    void analyzesEveryCorpusExample() throws IOException {
        assumeTrue(Files.isDirectory(CORPUS), "нет каталога corpus/ — проверять нечего");
        List<Path> examples;
        try (Stream<Path> entries = Files.list(CORPUS)) {
            examples = entries.filter(Files::isDirectory).sorted().toList();
        }
        assertThat(examples).as("в corpus/ нет ни одного примера").isNotEmpty();

        for (Path example : examples) {
            List<SourceFile> files = javaFiles(example);
            if (files.isEmpty()) {
                continue;
            }
            ProjectModel model = new JavaProjectParser().parse(files);
            assertThat(model.hasErrors())
                    .as("пример корпуса %s должен разбираться без ошибок", example.getFileName())
                    .isFalse();

            AnalysisReport report = Analyzer.withDefaultRules().analyze(model);
            System.out.printf("корпус %s: файлов %d, методов %d, находок %d%n",
                    example.getFileName(), files.size(), report.metrics().size(), report.findings().size());

            assertThat(report.metrics()).as("метрики по методам в %s", example.getFileName()).isNotEmpty();
            for (MethodMetrics metrics : report.metrics()) {
                assertThat(metrics.complexity()).as("Big O метода %s", metrics.name()).isNotNull();
                assertThat(metrics.cyclomatic()).isPositive();
            }
            for (Finding finding : report.findings()) {
                assertThat(finding.file()).as("файл находки %s", finding.id())
                        .isIn(files.stream().map(SourceFile::path).toList());
                assertThat(finding.startLine()).isPositive();
                assertThat(finding.endLine()).isGreaterThanOrEqualTo(finding.startLine());
                assertThat(finding.nodeIds()).as("узел графа для находки %s", finding.id()).isNotEmpty();
            }
        }
    }

    private static List<SourceFile> javaFiles(Path example) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(example)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String relative = CORPUS.relativize(path).toString().replace('\\', '/');
                files.add(new SourceFile(relative, Files.readString(path, StandardCharsets.UTF_8)));
            }
        }
        return files;
    }
}
