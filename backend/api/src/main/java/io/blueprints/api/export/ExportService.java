package io.blueprints.api.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.blueprints.api.diff.UnifiedDiff;
import io.blueprints.api.store.ProjectSession;
import io.blueprints.api.verify.VerifyService;
import io.blueprints.api.web.Dto.RunResult;
import io.blueprints.api.web.Dto.VerifyCase;
import io.blueprints.api.web.Dto.VerifyReport;
import io.blueprints.api.web.Dto.VerifyRequest;
import io.blueprints.core.model.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GET /export: zip с текущими Java-файлами и golden-master тестами JUnit 5 (артефакт сдачи «автотесты и результат
 * запуска»). Эталон — вывод исходной версии 1 на тех же входах, что и /verify; результат проверки текущей версии
 * лежит рядом (verify-report.json, README.md). Внутри — обычный Maven-проект: {@code mvn test}.
 */
@Service
public class ExportService {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAIN = Pattern.compile("static\\s+(public\\s+)?void\\s+main\\s*\\(");

    private final VerifyService verifyService;
    private final ObjectMapper json;
    private final int timeoutSeconds;

    public ExportService(VerifyService verifyService, ObjectMapper json,
                         @Value("${verify.timeout-ms:10000}") int timeoutMs) {
        this.verifyService = verifyService;
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.timeoutSeconds = Math.max(5, timeoutMs / 1000 * 2);
    }

    public record Export(String fileName, byte[] zip, VerifyReport report) {
    }

    public Export export(ProjectSession session) {
        int current = session.version();
        List<SourceFile> baseline = session.files(1);
        List<SourceFile> files = session.files(current);
        VerifyReport report = verifyService.verify(baseline, 1, files, current, session.savedInputs(),
                new VerifyRequest(null, true, false, null));
        String mainClass = findMainClass(files);
        String artifact = slug(session.name());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            String root = artifact + "-v" + current + "/";
            for (SourceFile f : files) {
                put(zip, root + "src/main/java/" + f.path(), f.content());
            }
            for (SourceFile f : baseline) {
                put(zip, root + "original/" + f.path(), f.content());
            }
            put(zip, root + "changes.diff", UnifiedDiff.of(baseline, files));
            put(zip, root + "pom.xml", template("pom.xml.tpl")
                    .replace("{{ARTIFACT}}", artifact)
                    .replace("{{VERSION}}", current + ".0"));
            put(zip, root + "src/test/java/GoldenMasterTest.java", template("GoldenMasterTest.java.tpl")
                    .replace("{{MAIN}}", mainClass == null ? "Main" : mainClass)
                    .replace("{{BASELINE}}", "1")
                    .replace("{{TIMEOUT}}", String.valueOf(timeoutSeconds)));
            int n = 0;
            for (VerifyCase c : report.cases()) {
                RunResult exp = c.expected();
                if (exp == null || exp.timedOut() || exp.exitCode() == null || c.input() == null || c.name().startsWith("Бенчмарк")) {
                    continue;
                }
                String id = String.format(Locale.ROOT, "golden/%02d", ++n);
                put(zip, root + id + ".name", c.name() + "\n");
                put(zip, root + id + ".in", c.input());
                put(zip, root + id + ".out", nullToEmpty(exp.stdout()));
                put(zip, root + id + ".err", nullToEmpty(exp.stderr()));
                put(zip, root + id + ".exit", exp.exitCode() + "\n");
            }
            put(zip, root + "verify-report.json", json.writeValueAsString(report));
            put(zip, root + "README.md", readme(session, report, mainClass, n));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Export(artifact + "-v" + current + ".zip", bytes.toByteArray(), report);
    }

    private static String readme(ProjectSession session, VerifyReport report, String mainClass, int goldens) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(session.name()).append(" — версия ").append(report.currentVersion())
                .append(" (экспорт Java Blueprints)\n\n");
        sb.append("| | |\n|---|---|\n");
        sb.append("| Эталон | версия ").append(report.baselineVersion()).append(" — исходный код, `original/` |\n");
        sb.append("| Текущая | версия ").append(report.currentVersion()).append(" — `src/main/java/`, изменения в `changes.diff` |\n");
        sb.append("| Точка входа | `").append(mainClass == null ? "не найдена" : mainClass).append("` |\n");
        sb.append("| Компиляция | ").append(report.compiled() ? "✅" : "❌").append(" |\n");
        sb.append("| Дифф-тесты | ").append(report.passed()).append(" / ").append(report.total())
                .append(report.behaviorPreserved() ? " — **поведение совпадает** ✅" : " — **поведение изменилось** ❌")
                .append(" |\n");
        sb.append("| Golden-master тестов | ").append(goldens).append(" (`golden/NN.in` → `.out` / `.err` / `.exit`) |\n\n");
        sb.append("## Запуск тестов\n\n```bash\nmvn test        # JDK 21+\n```\n\n");
        sb.append("`GoldenMasterTest` запускает текущую версию отдельным процессом на каждом `golden/NN.in` и сравнивает ")
                .append("stdout, stderr и код выхода с эталоном версии 1 — после той же нормализации, что и проверка ")
                .append("в веб-инструменте (переводы строк, identity-хеши `@HASH`, кадры стектрейса).\n\n");
        sb.append("## Результат проверки при экспорте\n\n| # | Случай | Итог | Что разошлось |\n|---|---|---|---|\n");
        int i = 0;
        for (VerifyCase c : report.cases()) {
            sb.append("| ").append(++i).append(" | ").append(escape(c.name())).append(" | ")
                    .append(c.ok() ? "✅" : "❌").append(" | ")
                    .append(c.mismatch() == null ? "" : escape(c.mismatch())).append(" |\n");
        }
        sb.append("\nПолный отчёт — `verify-report.json`.\n");
        return sb.toString();
    }

    static String findMainClass(List<SourceFile> files) {
        for (SourceFile file : files) {
            if (MAIN.matcher(file.content()).find()) {
                String simple = Path.of(file.path()).getFileName().toString().replaceFirst("\\.java$", "");
                Matcher pkg = PACKAGE.matcher(file.content());
                return pkg.find() ? pkg.group(1) + "." + simple : simple;
            }
        }
        return null;
    }

    static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "project" : s;
    }

    private static String escape(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String template(String name) {
        try (InputStream in = ExportService.class.getResourceAsStream("/export/" + name)) {
            if (in == null) {
                throw new IllegalStateException("нет шаблона " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
