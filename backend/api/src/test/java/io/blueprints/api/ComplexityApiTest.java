package io.blueprints.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /complexity: Big O выделенных блоков блок-схемы. */
@SpringBootTest
@AutoConfigureMockMvc
class ComplexityApiTest {

    private static final String SOURCE = """
            import java.util.*;

            public class Main {
                static int pairs(List<Integer> xs) {
                    int count = 0;
                    int first = xs.isEmpty() ? 0 : xs.get(0);
                    for (int a : xs) {
                        for (int b : xs) {
                            if (a + b == first) {
                                count++;
                            }
                        }
                    }
                    return count;
                }

                public static void main(String[] args) {
                    System.out.println(pairs(List.of(1, 2, 3)));
                }
            }
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void innerLoopIsQuadraticPlainStatementConstantWholeMethodQuadratic() throws Exception {
        String body = json.writeValueAsString(Map.of("files", List.of(Map.of("path", "Main.java", "content", SOURCE))));
        String id = json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        JsonNode overview = json.readTree(mvc.perform(get("/api/projects/" + id + "/graph")).andReturn().getResponse().getContentAsString());
        String method = null;
        for (JsonNode n : overview.get("nodes")) {
            if ("pairs".equals(n.get("label").asText())) {
                method = n.get("id").asText();
            }
        }
        JsonNode flow = json.readTree(mvc.perform(get("/api/projects/" + id + "/graph").param("level", "method").param("method", method))
                .andReturn().getResponse().getContentAsString());
        List<String> inner = new ArrayList<>();
        List<String> variables = new ArrayList<>();
        for (JsonNode n : flow.get("nodes")) {
            String code = n.path("code").asText("");
            if ("loop".equals(n.get("kind").asText()) && code.contains("b : xs")) {
                inner.add(n.get("id").asText());
            }
            if ("variable".equals(n.get("kind").asText())) {
                variables.add(n.get("id").asText());
            }
        }

        mvc.perform(post("/api/projects/" + id + "/complexity").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", 1, "nodeIds", inner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.current").value("O(n²)"));
        mvc.perform(post("/api/projects/" + id + "/complexity").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nodeIds", variables.subList(0, 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value("O(1)"));
        mvc.perform(post("/api/projects/" + id + "/complexity").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nodeIds", List.of(method + ":start")))))
                .andExpect(jsonPath("$.current").value("O(n²)"));
    }

    @Test
    void staleVersionAndEmptySelection() throws Exception {
        String body = json.writeValueAsString(Map.of("files", List.of(Map.of("path", "Main.java", "content", SOURCE))));
        String id = json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post("/api/projects/" + id + "/complexity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5,\"nodeIds\":[\"g1\"]}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/projects/" + id + "/complexity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeIds\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
