package io.blueprints.runner;

import io.blueprints.runner.RunModels.RunBatchResult;
import io.blueprints.runner.RunModels.RunResult;
import io.blueprints.runner.RunModels.RunnerBatchRequest;
import io.blueprints.runner.RunModels.SourceFile;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Каждый пример {@code corpus/} компилируется в песочнице, все его входы (кроме bench-*) выполняются без таймаута
 * и дважды дают одинаковый результат — иначе дифф-тесты verify на этом примере давали бы ложные расхождения.
 */
class CorpusSmokeTest {

    private static final Path CORPUS = Path.of("..", "..", "corpus");
    private final JavaSandbox sandbox = new JavaSandbox(1 << 20, 60_000, "256m");

    @TestFactory
    Stream<DynamicTest> everyCorpusExampleRunsDeterministically() throws IOException {
        assumeTrue(Files.isDirectory(CORPUS), "нет каталога corpus/");
        List<Path> examples;
        try (Stream<Path> dirs = Files.list(CORPUS)) {
            examples = dirs.filter(Files::isDirectory).sorted().toList();
        }
        assertThat(examples).isNotEmpty();
        return examples.stream().map(dir -> DynamicTest.dynamicTest(dir.getFileName().toString(), () -> check(dir)));
    }

    private void check(Path dir) throws Exception {
        List<SourceFile> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted()
                    .map(p -> new SourceFile(dir.relativize(p).toString(), read(p))).toList();
        }
        List<String> names = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        Path inputsDir = dir.resolve("inputs");
        if (Files.isDirectory(inputsDir)) {
            try (Stream<Path> list = Files.list(inputsDir)) {
                for (Path p : list.sorted().toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".txt") && !name.startsWith("bench-")) {
                        names.add(name);
                        inputs.add(read(p));
                    }
                }
            }
        }
        assertThat(inputs).as(dir + ": нет ни одного входа в inputs/").isNotEmpty();
        List<String> twice = new ArrayList<>(inputs);
        twice.addAll(inputs);

        RunBatchResult batch = sandbox.runBatch(new RunnerBatchRequest(files, null, twice, 20_000));

        assertThat(batch.compile().ok()).as(dir + " компилируется: " + batch.compile().output()).isTrue();
        for (int i = 0; i < inputs.size(); i++) {
            RunResult first = batch.results().get(i);
            RunResult second = batch.results().get(i + inputs.size());
            String where = dir.getFileName() + "/" + names.get(i);
            assertThat(first.timedOut()).as(where + ": таймаут").isFalse();
            assertThat(first.stdout()).as(where + ": есть вывод").isNotEmpty();
            assertThat(second.stdout()).as(where + ": stdout детерминирован").isEqualTo(first.stdout());
            assertThat(second.stderr()).as(where + ": stderr детерминирован").isEqualTo(first.stderr());
            assertThat(second.exitCode()).as(where + ": код выхода детерминирован").isEqualTo(first.exitCode());
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
