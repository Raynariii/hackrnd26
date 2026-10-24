package io.blueprints.runner;

import io.blueprints.runner.RunModels.CompileResult;
import io.blueprints.runner.RunModels.Problem;
import io.blueprints.runner.RunModels.RunBatchResult;
import io.blueprints.runner.RunModels.RunResult;
import io.blueprints.runner.RunModels.RunnerBatchRequest;
import io.blueprints.runner.RunModels.RunnerRequest;
import io.blueprints.runner.RunModels.SourceFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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

    /** Подставляется вместо пути временной папки в выводе, чтобы он не зависел от запуска. */
    public static final String SANDBOX_DIR = "<sandbox>";

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAIN = Pattern.compile("static\\s+(public\\s+)?void\\s+main\\s*\\(");
    private static final Pattern JAVAC_ERROR = Pattern.compile("^(.+?\\.java):(\\d+): (?:error|ошибка): (.+)$", Pattern.MULTILINE);
    /** mainClass приходит из API: без проверки строка вида "-version" станет опцией JVM. */
    private static final Pattern FQN = Pattern.compile("^[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*$");
    /** Переменные окружения, из-за которых JVM печатает в stderr лишние строки («Picked up …»). */
    private static final List<String> NOISY_ENV = List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");

    private final int maxOutputBytes;
    private final int maxTimeoutMs;
    private final String jvmMaxHeap;
    private final int maxBatchInputs;

    @Autowired
    public JavaSandbox(@Value("${sandbox.max-output-bytes:1048576}") int maxOutputBytes,
                       @Value("${sandbox.max-timeout-ms:60000}") int maxTimeoutMs,
                       @Value("${sandbox.jvm-max-heap:256m}") String jvmMaxHeap,
                       @Value("${sandbox.max-batch-inputs:100}") int maxBatchInputs) {
        this.maxOutputBytes = maxOutputBytes;
        this.maxTimeoutMs = maxTimeoutMs;
        this.jvmMaxHeap = jvmMaxHeap;
        this.maxBatchInputs = maxBatchInputs;
    }

    public JavaSandbox(int maxOutputBytes, int maxTimeoutMs, String jvmMaxHeap) {
        this(maxOutputBytes, maxTimeoutMs, jvmMaxHeap, 100);
    }

    public RunResult run(RunnerRequest request) throws IOException, InterruptedException {
        RunBatchResult batch = runBatch(new RunnerBatchRequest(request.files(), request.mainClass(),
                List.of(request.stdin() == null ? "" : request.stdin()), request.timeoutMs()));
        if (!batch.results().isEmpty()) {
            return batch.results().get(0);
        }
        return new RunResult(batch.compile(), "", "", null, 0, false);
    }

    /** Компилирует один раз и запускает на каждом входе по очереди (G2: verify не платит за javac на каждый вход). */
    public RunBatchResult runBatch(RunnerBatchRequest request) throws IOException, InterruptedException {
        if (request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("Нет файлов для компиляции");
        }
        if (request.mainClass() != null && !FQN.matcher(request.mainClass()).matches()) {
            throw new IllegalArgumentException("Некорректное имя mainClass: " + request.mainClass());
        }
        List<String> inputs = request.inputs() == null ? List.of() : request.inputs();
        if (inputs.size() > maxBatchInputs) {
            throw new IllegalArgumentException("Слишком много входов: " + inputs.size() + " (максимум " + maxBatchInputs + ")");
        }
        int timeoutMs = Math.min(request.timeoutMs() == null ? 10_000 : request.timeoutMs(), maxTimeoutMs);
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

            List<String> javac = new ArrayList<>(List.of("javac", "-encoding", "UTF-8", "-d", out.toString()));
            javac.addAll(sources);
            ProcessOutcome compiled = exec(javac, work, "", Math.max(timeoutMs, 30_000));
            String rawCompileOutput = compiled.stderr + compiled.stdout;
            if (compiled.timedOut || compiled.exitCode != 0) {
                List<Problem> problems = parseProblems(rawCompileOutput, src);
                return new RunBatchResult(new CompileResult(false, problems, scrub(rawCompileOutput, work)), List.of());
            }
            CompileResult ok = new CompileResult(true, List.of(), scrub(rawCompileOutput, work));

            String mainClass = request.mainClass() != null ? request.mainClass() : findMainClass(request.files());
            List<RunResult> results = new ArrayList<>();
            for (String stdin : inputs) {
                if (mainClass == null) {
                    results.add(new RunResult(ok, "", "Не найден класс с методом main", null, 0, false));
                    continue;
                }
                ProcessOutcome run = exec(List.of("java", "-Xmx" + jvmMaxHeap, "-Dfile.encoding=UTF-8",
                                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", out.toString(), mainClass),
                        work, stdin == null ? "" : stdin, timeoutMs);
                results.add(new RunResult(ok, scrub(run.stdout, work), scrub(run.stderr, work),
                        run.timedOut ? null : run.exitCode, run.timeMs, run.timedOut));
            }
            return new RunBatchResult(ok, results);
        } finally {
            deleteRecursively(work);
        }
    }

    /** Приёмник событий потокового запуска (SSE): compile, stdout, stderr, exit. Вызывается из нескольких потоков. */
    @FunctionalInterface
    public interface StreamSink {
        void event(String name, Object data) throws IOException;
    }

    /**
     * Потоковый запуск для консоли: компиляция, затем вывод программы отдаётся кусками по мере появления (а не после
     * завершения). Те же ограничения, что у run: таймаут с уничтожением дерева процессов, лимит вывода, без шума JVM.
     */
    public void runStreaming(RunnerRequest request, StreamSink sink) throws IOException, InterruptedException {
        if (request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("Нет файлов для компиляции");
        }
        if (request.mainClass() != null && !FQN.matcher(request.mainClass()).matches()) {
            throw new IllegalArgumentException("Некорректное имя mainClass: " + request.mainClass());
        }
        int timeoutMs = Math.min(request.timeoutMs() == null ? 10_000 : request.timeoutMs(), maxTimeoutMs);
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
            List<String> javac = new ArrayList<>(List.of("javac", "-encoding", "UTF-8", "-d", out.toString()));
            javac.addAll(sources);
            ProcessOutcome compiled = exec(javac, work, "", Math.max(timeoutMs, 30_000));
            String rawCompileOutput = compiled.stderr + compiled.stdout;
            if (compiled.timedOut || compiled.exitCode != 0) {
                sink.event("compile", new CompileResult(false, parseProblems(rawCompileOutput, src), scrub(rawCompileOutput, work)));
                return;
            }
            sink.event("compile", new CompileResult(true, List.of(), scrub(rawCompileOutput, work)));
            String mainClass = request.mainClass() != null ? request.mainClass() : findMainClass(request.files());
            if (mainClass == null) {
                sink.event("stderr", java.util.Map.of("text", "Не найден класс с методом main"));
                sink.event("exit", new StreamExit(null, 0, false));
                return;
            }

            Path stdinFile = work.resolve("stdin");
            Files.writeString(stdinFile, request.stdin() == null ? "" : request.stdin(), StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(List.of("java", "-Xmx" + jvmMaxHeap, "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", out.toString(), mainClass))
                    .directory(work.toFile())
                    .redirectInput(stdinFile.toFile());
            NOISY_ENV.forEach(builder.environment()::remove);
            long start = System.nanoTime();
            Process process = builder.start();
            java.util.concurrent.atomic.AtomicLong budget = new java.util.concurrent.atomic.AtomicLong(maxOutputBytes);
            Thread stdout = pump(process.getInputStream(), "stdout", sink, budget, work);
            Thread stderr = pump(process.getErrorStream(), "stderr", sink, budget, work);
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
            }
            stdout.join(2_000);
            stderr.join(2_000);
            long timeMs = (System.nanoTime() - start) / 1_000_000;
            sink.event("exit", new StreamExit(finished ? process.exitValue() : null, timeMs, !finished));
        } finally {
            deleteRecursively(work);
        }
    }

    /** Итог потокового запуска. */
    public record StreamExit(Integer exitCode, long timeMs, boolean timedOut) {
    }

    /** Читает поток процесса кусками и отдаёт их в sink; после лимита вывода дочитывает молча, чтобы процесс не встал. */
    private static Thread pump(InputStream in, String name, StreamSink sink,
                               java.util.concurrent.atomic.AtomicLong budget, Path work) {
        Thread t = new Thread(() -> {
            char[] buf = new char[4096];
            boolean truncated = false;
            try (java.io.Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                int n;
                while ((n = reader.read(buf)) != -1) {
                    if (truncated) {
                        continue;
                    }
                    String chunk = scrub(new String(buf, 0, n), work);
                    long left = budget.addAndGet(-chunk.getBytes(StandardCharsets.UTF_8).length);
                    if (left < 0) {
                        truncated = true;
                        sink.event(name, java.util.Map.of("text", "\n…[вывод обрезан]"));
                        continue;
                    }
                    if (!chunk.isEmpty()) {
                        sink.event(name, java.util.Map.of("text", chunk));
                    }
                }
            } catch (IOException ignored) {
                // клиент отключился или процесс убит по таймауту — поток просто заканчивается
            }
        }, "sandbox-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private ProcessOutcome exec(List<String> command, Path dir, String stdin, int timeoutMs)
            throws IOException, InterruptedException {
        Path stdoutFile = dir.resolve("stdout-" + System.nanoTime());
        Path stderrFile = dir.resolve("stderr-" + System.nanoTime());
        Path stdinFile = dir.resolve("stdin-" + System.nanoTime());
        Files.writeString(stdinFile, stdin, StandardCharsets.UTF_8);
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectInput(stdinFile.toFile())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile());
            NOISY_ENV.forEach(builder.environment()::remove);
            long start = System.nanoTime();
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly().waitFor();
            }
            long timeMs = (System.nanoTime() - start) / 1_000_000;
            return new ProcessOutcome(read(stdoutFile), read(stderrFile), finished ? process.exitValue() : -1, timeMs, !finished);
        } finally {
            Files.deleteIfExists(stdoutFile);
            Files.deleteIfExists(stderrFile);
            Files.deleteIfExists(stdinFile);
        }
    }

    /** Читает не больше maxOutputBytes + 1 байта: огромный вывод не должен съесть память runner-а. */
    private String read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(maxOutputBytes + 1);
            if (bytes.length <= maxOutputBytes) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return new String(bytes, 0, maxOutputBytes, StandardCharsets.UTF_8) + "\n…[вывод обрезан]";
        }
    }

    /** Убирает путь временной папки (меняется на каждый запуск) и строки «Picked up JAVA_TOOL_OPTIONS». */
    static String scrub(String text, Path work) {
        String result = text.replace(work.resolve("src") + "/", "")
                .replace(work.resolve("src").toString(), SANDBOX_DIR)
                .replace(work.toString(), SANDBOX_DIR);
        return result.replaceAll("(?m)^Picked up (?:JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS):.*\\R?", "");
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
