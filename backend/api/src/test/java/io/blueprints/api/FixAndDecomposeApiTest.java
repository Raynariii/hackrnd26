package io.blueprints.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.blueprints.api.runner.RunnerClient;
import io.blueprints.api.web.Dto.CompileInfo;
import io.blueprints.api.web.Dto.RunBatchResult;
import io.blueprints.api.web.Dto.RunResult;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /fixes и /decompose: песочница подменена — реальный запуск javac проверяется в runner. */
@SpringBootTest
@AutoConfigureMockMvc
class FixAndDecomposeApiTest {

    private static final String SOURCE = """
            import java.util.*;

            public class Main {
                static String join(List<String> words) {
                    String result = "";
                    for (String w : words) {
                        result += w + ",";
                    }
                    return result;
                }

                public static void main(String[] args) {
                    System.out.println(join(List.of("a", "b", "c")));
                }
            }
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    RunnerClient runner;

    private static final CompileInfo OK = new CompileInfo(true, List.of(), "");

    /** «Программа» печатает одно и то же — если только в коде нет маркера BROKEN. */
    private void sandboxPrints(String stdoutForFixed) {
        when(runner.runBatch(anyList(), anyList(), anyInt())).thenAnswer(inv -> {
            List<SourceFile> files = inv.getArgument(0);
            List<String> inputs = inv.getArgument(1);
            boolean fixed = files.get(0).content().contains("StringBuilder");
            String out = fixed ? stdoutForFixed : "a,b,c,\n";
            return new RunBatchResult(OK, inputs.stream().map(i -> new RunResult(OK, out, "", 0, 3, false)).toList());
        });
    }

    private String createProject() throws Exception {
        String body = json.writeValueAsString(Map.of("files", List.of(Map.of("path", "Main.java", "content", SOURCE))));
        String response = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    private String concatFindingId(String id) throws Exception {
        String response = mvc.perform(get("/api/projects/" + id + "/diagnostics")).andReturn().getResponse().getContentAsString();
        for (JsonNode f : json.readTree(response).get("findings")) {
            if (f.get("rule").asText().equals("string-concat-in-loop")) {
                return f.get("id").asText();
            }
        }
        throw new AssertionError("нет находки string-concat-in-loop: " + response);
    }

    @Test
    void fixPassesQualityGateAndCreatesNewVersionWithDiff() throws Exception {
        sandboxPrints("a,b,c,\n");
        String id = createProject();
        String finding = concatFindingId(id);

        String response = mvc.perform(post("/api/projects/" + id + "/fixes/" + finding)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.version").value(2))
                .andExpect(jsonPath("$.verify.behaviorPreserved").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = json.readTree(response);
        assertThat(result.get("diff").asText()).startsWith("--- a/Main.java").contains("+").contains("StringBuilder");
        assertThat(result.get("state").get("files").get(0).get("content").asText()).contains("StringBuilder");
    }

    @Test
    void fixThatChangesBehaviorIsRejectedAndSessionUntouched() throws Exception {
        sandboxPrints("a,b,c\n");
        String id = createProject();
        String finding = concatFindingId(id);

        mvc.perform(post("/api/projects/" + id + "/fixes/" + finding).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("quality_gate_failed"));
        mvc.perform(get("/api/projects/" + id)).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void unknownFindingAndStaleVersion() throws Exception {
        sandboxPrints("a,b,c,\n");
        String id = createProject();
        mvc.perform(post("/api/projects/" + id + "/fixes/nope").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("fix_not_applicable"));
        mvc.perform(post("/api/projects/" + id + "/fixes/" + concatFindingId(id)).contentType(MediaType.APPLICATION_JSON).content("{\"version\":7}"))
                .andExpect(status().isConflict());
    }

    @Test
    void decomposeProposesWithoutChangingTheProject() throws Exception {
        String id = createProject();
        mvc.perform(post("/api/projects/" + id + "/decompose").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"apply\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal").isArray())
                .andExpect(jsonPath("$.notes").isArray())
                .andExpect(jsonPath("$.result").doesNotExist());
        mvc.perform(get("/api/projects/" + id)).andExpect(jsonPath("$.version").value(1));
    }

    /** Небольшой god-класс: Decomposer разносит static-методы по Parser / Analyzer / Optimizer. */
    private static final String MONOLITH = """
            import java.util.*;

            public class Main {
                static List<Integer> parseNumbers(String line) {
                    List<Integer> out = new ArrayList<>();
                    for (String p : line.trim().split("\\\\s+")) {
                        if (!p.isEmpty()) {
                            out.add(Integer.parseInt(p));
                        }
                    }
                    return out;
                }

                static String readLine(Scanner in) {
                    return in.hasNextLine() ? in.nextLine() : "";
                }

                static int analyzeMax(List<Integer> xs) {
                    int max = Integer.MIN_VALUE;
                    for (int x : xs) {
                        max = Math.max(max, x);
                    }
                    return max;
                }

                static double computeAverage(List<Integer> xs) {
                    return xs.isEmpty() ? 0 : xs.stream().mapToInt(Integer::intValue).sum() / (double) xs.size();
                }

                static List<Integer> sortValues(List<Integer> xs) {
                    List<Integer> copy = new ArrayList<>(xs);
                    Collections.sort(copy);
                    return copy;
                }

                static List<Integer> dedupValues(List<Integer> xs) {
                    return new ArrayList<>(new LinkedHashSet<>(xs));
                }

                public static void main(String[] args) {
                    Scanner in = new Scanner(System.in);
                    List<Integer> xs = parseNumbers(readLine(in));
                    System.out.println("max=" + (xs.isEmpty() ? "-" : analyzeMax(xs)) + " avg=" + computeAverage(xs));
                    System.out.println("sorted=" + sortValues(dedupValues(xs)));
                }
            }
            """;

    private String createMonolith() throws Exception {
        String body = json.writeValueAsString(Map.of("files", List.of(Map.of("path", "Main.java", "content", MONOLITH))));
        return json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    /** Песочница: одинаковый вывод — или другой у разбитой версии (несколько файлов), если breaks. */
    private void sandboxForDecompose(boolean breaks) {
        when(runner.runBatch(anyList(), anyList(), anyInt())).thenAnswer(inv -> {
            List<SourceFile> files = inv.getArgument(0);
            List<String> inputs = inv.getArgument(1);
            String out = breaks && files.size() > 1 ? "max=1\n" : "max=3\n";
            return new RunBatchResult(OK, inputs.stream().map(i -> new RunResult(OK, out, "", 0, 3, false)).toList());
        });
    }

    @Test
    void decomposeApplyPassesQualityGateAndCreatesNewClasses() throws Exception {
        sandboxForDecompose(false);
        String id = createMonolith();

        String response = mvc.perform(post("/api/projects/" + id + "/decompose").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"apply\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.state.version").value(2))
                .andExpect(jsonPath("$.result.verify.behaviorPreserved").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = json.readTree(response);
        List<String> paths = new java.util.ArrayList<>();
        result.get("result").get("state").get("files").forEach(f -> paths.add(f.get("path").asText()));
        assertThat(paths).contains("Main.java", "Parser.java", "Analyzer.java", "Optimizer.java");
        assertThat(result.get("result").get("diff").asText()).contains("+++ b/Parser.java");
        assertThat(result.get("result").get("description").asText()).isNotBlank();
        assertThat(result.get("notes").size()).isPositive();
    }

    @Test
    void decomposeApplyThatChangesBehaviorIsRejected() throws Exception {
        sandboxForDecompose(true);
        String id = createMonolith();

        mvc.perform(post("/api/projects/" + id + "/decompose").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"apply\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("quality_gate_failed"));
        mvc.perform(get("/api/projects/" + id)).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void decomposeApplyStaleVersionAndNothingToSplit() throws Exception {
        sandboxForDecompose(false);
        String id = createMonolith();
        mvc.perform(post("/api/projects/" + id + "/decompose").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":9,\"apply\":true}"))
                .andExpect(status().isConflict());

        String small = createProject();
        mvc.perform(post("/api/projects/" + small + "/decompose").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"apply\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("decompose_not_applicable"));
    }
}
