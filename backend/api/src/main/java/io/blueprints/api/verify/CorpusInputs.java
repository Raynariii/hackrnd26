package io.blueprints.api.verify;

import io.blueprints.core.model.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Входы из {@code corpus/<пример>/inputs/}: если загруженный проект совпадает с примером корпуса
 * (по содержимому .java-файлов), его входы автоматически становятся дифф-тестами, а {@code bench-<n>.txt} — бенчмарком.
 */
@Component
public class CorpusInputs {

    private static final Logger log = LoggerFactory.getLogger(CorpusInputs.class);
    private static final Pattern BENCH = Pattern.compile("bench-(\\d+)\\.txt");

    public record NamedInput(String name, String stdin) {
    }

    public record BenchInput(int n, String name, String stdin) {
    }

    public record Example(String name, List<NamedInput> inputs, List<BenchInput> bench) {
    }

    private final Path corpusDir;

    public CorpusInputs(@Value("${verify.corpus-dir:../../corpus}") String corpusDir) {
        this.corpusDir = Path.of(corpusDir).toAbsolutePath().normalize();
    }

    /** Пример корпуса, чьи .java-файлы совпадают с файлами проекта (без учёта переводов строк и краевых пробелов). */
    public Optional<Example> find(List<SourceFile> files) {
        if (!Files.isDirectory(corpusDir)) {
            return Optional.empty();
        }
        List<String> wanted = files.stream().map(f -> canonical(f.content())).sorted().toList();
        try (Stream<Path> dirs = Files.list(corpusDir)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                if (wanted.equals(javaContents(dir))) {
                    return Optional.of(load(dir));
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать корпус {}: {}", corpusDir, e.toString());
        }
        return Optional.empty();
    }

    private static List<String> javaContents(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            List<String> contents = new ArrayList<>();
            for (Path p : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                contents.add(canonical(Files.readString(p, StandardCharsets.UTF_8)));
            }
            contents.sort(null);
            return contents;
        }
    }

    private Example load(Path dir) throws IOException {
        List<NamedInput> inputs = new ArrayList<>();
        List<BenchInput> bench = new ArrayList<>();
        Path inputsDir = dir.resolve("inputs");
        if (Files.isDirectory(inputsDir)) {
            try (Stream<Path> files = Files.list(inputsDir)) {
                for (Path p : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                    String name = "corpus/" + dir.getFileName() + "/inputs/" + p.getFileName();
                    String stdin = Files.readString(p, StandardCharsets.UTF_8);
                    Matcher m = BENCH.matcher(p.getFileName().toString());
                    if (m.matches()) {
                        bench.add(new BenchInput(Integer.parseInt(m.group(1)), name, stdin));
                    } else {
                        inputs.add(new NamedInput(name, stdin));
                    }
                }
            }
        }
        bench.sort((a, b) -> Integer.compare(a.n(), b.n()));
        return new Example(dir.getFileName().toString(), inputs, bench);
    }

    private static String canonical(String content) {
        return content.replace("\r\n", "\n").strip();
    }
}
