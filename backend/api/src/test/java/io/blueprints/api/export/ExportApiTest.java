package io.blueprints.api.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.blueprints.api.runner.RunnerClient;
import io.blueprints.api.web.Dto.CompileInfo;
import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.api.web.Dto.RunResult;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /export: состав архива, эталоны из версии 1, сгенерированный тест компилируется против JUnit 5. */
@SpringBootTest
@AutoConfigureMockMvc
class ExportApiTest {

    private static final String SOURCE = """
            public class Main {
                public static void main(String[] args) {
                    System.out.println(new java.util.Scanner(System.in).nextLine().length());
                }
            }
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    RunnerClient runner;

    @TempDir
    Path tmp;

    @Test
    void exportContainsSourcesGoldensAndCompilableJUnitTest() throws Exception {
        CompileInfo ok = new CompileInfo(true, List.of(), "");
        when(runner.runBatch(anyList(), anyList(), anyInt())).thenAnswer(inv -> {
            List<String> inputs = inv.getArgument(1);
            return new RunBatchResult(ok, inputs.stream().map(in -> in.isEmpty()
                    ? new RunResult(ok, "", "Exception in thread \"main\" java.util.NoSuchElementException: No line found\n\tat Main.main(Main.java:3)\n", 1, 5, false)
                    : new RunResult(ok, in.strip().length() + "\n", "", 0, 5, false)).toList());
        });
        String body = json.writeValueAsString(Map.of("name", "Demo Склад", "files", List.of(Map.of("path", "Main.java", "content", SOURCE))));
        String id = json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        MockHttpServletResponse response = mvc.perform(get("/api/projects/" + id + "/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("X-Behavior-Preserved", "true"))
                .andReturn().getResponse();
        assertThat(response.getHeader("Content-Disposition")).contains("demo-v1.zip");

        Map<String, String> entries = unzip(response.getContentAsByteArray());
        assertThat(entries).containsKeys("demo-v1/pom.xml", "demo-v1/src/main/java/Main.java",
                "demo-v1/original/Main.java", "demo-v1/src/test/java/GoldenMasterTest.java",
                "demo-v1/verify-report.json", "demo-v1/README.md", "demo-v1/changes.diff",
                "demo-v1/golden/01.in", "demo-v1/golden/01.out", "demo-v1/golden/01.err", "demo-v1/golden/01.exit",
                "demo-v1/golden/01.name");
        assertThat(entries.get("demo-v1/src/main/java/Main.java")).isEqualTo(SOURCE);
        assertThat(entries.get("demo-v1/README.md")).contains("поведение совпадает").contains("mvn test");
        assertThat(entries.get("demo-v1/golden/01.name")).contains("Граничный: пустой ввод");
        assertThat(entries.get("demo-v1/golden/01.exit").strip()).isEqualTo("1");
        String test = entries.get("demo-v1/src/test/java/GoldenMasterTest.java");
        assertThat(test).contains("MAIN_CLASS = \"Main\"").doesNotContain("{{");

        // Сгенерированный тест — валидный Java против JUnit 5 из classpath этого теста.
        Path src = Files.writeString(tmp.resolve("GoldenMasterTest.java"), test);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, "-d", tmp.toString(), "-cp", System.getProperty("java.class.path"), src.toString());
        assertThat(rc).as(err.toString(StandardCharsets.UTF_8)).isZero();
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
