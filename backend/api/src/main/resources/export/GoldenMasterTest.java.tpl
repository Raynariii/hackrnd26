import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-master тесты, сгенерированные Java Blueprints: эталон — вывод исходной версии {{BASELINE}} на входах
 * из golden/NN.in; тест запускает текущую версию ({{MAIN}}) отдельным процессом на тех же входах и сравнивает
 * stdout, stderr и код выхода после той же нормализации, что и /verify: переводы строк, identity-хеши
 * (Order@1b6d3586 → Order@HASH), кадры стектрейса в stderr (номера строк меняются после рефакторинга).
 */
class GoldenMasterTest {

    private static final String MAIN_CLASS = "{{MAIN}}";
    private static final Path GOLDEN = Path.of("golden");
    private static final long TIMEOUT_SECONDS = {{TIMEOUT}};

    @TestFactory
    Stream<DynamicTest> behaviourMatchesBaseline() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.list(GOLDEN)) {
            for (Path in : files.filter(p -> p.toString().endsWith(".in")).sorted().toList()) {
                String id = in.getFileName().toString().replace(".in", "");
                String name = read(GOLDEN.resolve(id + ".name")).strip();
                tests.add(DynamicTest.dynamicTest(id + " — " + name, () -> check(id)));
            }
        }
        assertTrue(!tests.isEmpty(), "нет ни одного эталона в golden/");
        return tests.stream();
    }

    private void check(String id) throws Exception {
        Path out = Files.createTempFile("gm-out", ".txt");
        Path err = Files.createTempFile("gm-err", ".txt");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8", "-cp", Path.of("target", "classes").toString(), MAIN_CLASS)
                .redirectInput(GOLDEN.resolve(id + ".in").toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile());
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().remove("_JAVA_OPTIONS");
        pb.environment().remove("JDK_JAVA_OPTIONS");
        Process process = pb.start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "не завершилась за " + TIMEOUT_SECONDS + " с");
        assertEquals(read(GOLDEN.resolve(id + ".exit")).strip(), String.valueOf(process.exitValue()), "код выхода");
        assertEquals(normalize(read(GOLDEN.resolve(id + ".out"))), normalize(read(out)), "stdout");
        assertEquals(normalizeStderr(read(GOLDEN.resolve(id + ".err"))), normalizeStderr(read(err)), "stderr");
    }

    static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?m)^(?:Picked up (?:JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS):.*|NOTE: Picked up .*)\\n?", "")
                .replaceAll("([\\w$\\]\\[;])@[0-9a-f]{4,8}\\b", "$1@HASH");
    }

    static String normalizeStderr(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : normalize(text).split("\n", -1)) {
            if (!line.matches("^\\s+(?:at\\s.*|\\.\\.\\.\\s*\\d+\\s+(?:more|common frames omitted))$")) {
                kept.add(line);
            }
        }
        return String.join("\n", kept).strip();
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
