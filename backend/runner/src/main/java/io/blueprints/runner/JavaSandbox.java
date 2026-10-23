package io.blueprints.runner;

import io.blueprints.runner.RunModels.CompileResult;
import io.blueprints.runner.RunModels.Problem;
import io.blueprints.runner.RunModels.RunResult;
import io.blueprints.runner.RunModels.RunnerRequest;
import io.blueprints.runner.RunModels.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Компиляция и запуск во временной папке отдельными процессами javac/java.
 * Изоляция (нет сети, лимиты памяти/процессов, read-only FS) обеспечивается контейнером — см. docker-compose.yml.
 */
@Component
public class JavaSandbox {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAIN = Pattern.compile("static\\s+(public\\s+)?void\\s+main\\s*\\(");
    private static final Pattern JAVAC_ERROR = Pattern.compile("^(.+?\\.java):(\\d+): (?:error|ошибка): (.+)$", Pattern.MULTILINE);

    private final int maxOutputBytes;
    private final int maxTimeoutMs;
    private final String jvmMaxHeap;

    public JavaSandbox(@Value("${sandbox.max-output-bytes:1048576}") int maxOutputBytes,
                       @Value("${sandbox.max-timeout-ms:60000}") int maxTimeoutMs,
                       @Value("${sandbox.jvm-max-heap:256m}") String jvmMaxHeap) {
        this.maxOutputBytes = maxOutputBytes;
        this.maxTimeoutMs = maxTimeoutMs;
        this.jvmMaxHeap = jvmMaxHeap;
    }

    public RunResult run(RunnerRequest request) throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("run-");
        try {
            Path src = Files.createDirectories(work.resolve("src"));
            Path out = Files.createDirectories(work.resolve("out"));
            List<String> sources = new ArrayList<>();
            for (SourceFile file : request.files()) {
                Path target = src.resolve(file.path()).normalize();
                if (!target.startsWith(src)) {
                    throw new IllegalArgumentException("Недопустимый путь файла: " + file.path());
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
                sources.add(target.toString());
            }
            int timeoutMs = Math.min(request.timeoutMs() == null ? 10_000 : request.timeoutMs(), maxTimeoutMs);

            List<String> javac = new ArrayList<>(List.of("javac", "-encoding", "UTF-8", "-d", out.toString()));
            javac.addAll(sources);
            ProcessOutcome compiled = exec(javac, work, "", timeoutMs);
            String compileOutput = compiled.stderr + compiled.stdout;
            if (compiled.timedOut || compiled.exitCode != 0) {
                return new RunResult(new CompileResult(false, parseProblems(compileOutput, src), compileOutput),
                        "", "", null, compiled.timeMs, compiled.timedOut);
            }

            String mainClass = request.mainClass() != null ? request.mainClass() : findMainClass(request.files());
            if (mainClass == null) {
                return new RunResult(new CompileResult(true, List.of(), compileOutput),
                        "", "Не найден класс с методом main", null, 0, false);
            }
            ProcessOutcome run = exec(List.of("java", "-Xmx" + jvmMaxHeap, "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", out.toString(), mainClass),
                    work, request.stdin() == null ? "" : request.stdin(), timeoutMs);
            return new RunResult(new CompileResult(true, List.of(), compileOutput),
                    run.stdout, run.stderr, run.timedOut ? null : run.exitCode, run.timeMs, run.timedOut);
        } finally {
            deleteRecursively(work);
        }
    }

    private ProcessOutcome exec(List<String> command, Path dir, String stdin, int timeoutMs)
            throws IOException, InterruptedException {
        Path stdoutFile = dir.resolve("stdout-" + System.nanoTime());
        Path stderrFile = dir.resolve("stderr-" + System.nanoTime());
        Path stdinFile = dir.resolve("stdin-" + System.nanoTime());
        Files.writeString(stdinFile, stdin, StandardCharsets.UTF_8);
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectInput(stdinFile.toFile())
                .redirectOutput(stdoutFile.toFile())
                .redirectError(stderrFile.toFile())
                .start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly().waitFor();
        }
        long timeMs = (System.nanoTime() - start) / 1_000_000;
        return new ProcessOutcome(read(stdoutFile), read(stderrFile), finished ? process.exitValue() : -1, timeMs, !finished);
    }

    private String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length <= maxOutputBytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, 0, maxOutputBytes, StandardCharsets.UTF_8) + "\n…[вывод обрезан]";
    }

    static String findMainClass(List<SourceFile> files) {
        for (SourceFile file : files) {
            if (MAIN.matcher(file.content()).find()) {
                String simpleName = Path.of(file.path()).getFileName().toString().replaceFirst("\\.java$", "");
                Matcher pkg = PACKAGE.matcher(file.content());
                return pkg.find() ? pkg.group(1) + "." + simpleName : simpleName;
            }
        }
        return null;
    }

    private static List<Problem> parseProblems(String output, Path src) {
        List<Problem> problems = new ArrayList<>();
        Matcher m = JAVAC_ERROR.matcher(output);
        while (m.find()) {
            String file = m.group(1).startsWith(src.toString())
                    ? src.relativize(Path.of(m.group(1))).toString()
                    : m.group(1);
            problems.add(new Problem(file, Integer.parseInt(m.group(2)), null, m.group(3)));
        }
        return problems;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private record ProcessOutcome(String stdout, String stderr, int exitCode, long timeMs, boolean timedOut) {
    }
}
