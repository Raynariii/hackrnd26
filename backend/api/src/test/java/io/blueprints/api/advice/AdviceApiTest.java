package io.blueprints.api.advice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.blueprints.analysis.llm.Advice;
import io.blueprints.analysis.llm.AdviceRequest;
import io.blueprints.analysis.llm.OptimizationAdvisor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /advice: советчик подменён — сеть и ключ не нужны. */
@SpringBootTest
@AutoConfigureMockMvc
class AdviceApiTest {

    private static final String SOURCE = """
            import java.util.*;

            public class Main {
                static int other() {
                    return 1;
                }

                static String join(List<String> words) {
                    String result = "";
                    for (String w : words) {
                        result += w + ",";
                    }
                    return result;
                }

                public static void main(String[] args) {
                    System.out.println(join(List.of("a", "b")));
                }
            }
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    OptimizationAdvisor advisor;

    private String[] projectAndFinding() throws Exception {
        String body = json.writeValueAsString(Map.of("files", List.of(Map.of("path", "Main.java", "content", SOURCE))));
        String id = json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        for (JsonNode f : json.readTree(mvc.perform(get("/api/projects/" + id + "/diagnostics"))
                .andReturn().getResponse().getContentAsString()).get("findings")) {
            if (f.get("rule").asText().equals("string-concat-in-loop")) {
                return new String[]{id, f.get("id").asText()};
            }
        }
        throw new AssertionError("нет находки string-concat-in-loop");
    }

    @Test
    void sendsFindingWithItsMethodCodeAndReturnsAdvice() throws Exception {
        when(advisor.enabled()).thenReturn(true);
        when(advisor.advise(any())).thenReturn(Optional.of(new Advice("Используйте StringBuilder", "claude-test")));
        String[] pf = projectAndFinding();

        mvc.perform(post("/api/projects/" + pf[0] + "/advice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingId\":\"" + pf[1] + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.findingId").value(pf[1]))
                .andExpect(jsonPath("$.suggestion").value("Используйте StringBuilder"))
                .andExpect(jsonPath("$.model").value("claude-test"));

        ArgumentCaptor<AdviceRequest> sent = ArgumentCaptor.forClass(AdviceRequest.class);
        verify(advisor).advise(sent.capture());
        assertThat(sent.getValue().ruleId()).isEqualTo("string-concat-in-loop");
        assertThat(sent.getValue().code()).startsWith("    static String join(").contains("result += w")
                .endsWith("    }").doesNotContain("other()").doesNotContain("main(");
    }

    @Test
    void disabledAdvisorGives204WithoutCalls() throws Exception {
        when(advisor.enabled()).thenReturn(false);
        String[] pf = projectAndFinding();

        mvc.perform(post("/api/projects/" + pf[0] + "/advice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingId\":\"" + pf[1] + "\"}"))
                .andExpect(status().isNoContent());
        verify(advisor, never()).advise(any());
    }

    @Test
    void unknownFindingAndFailedAdvisor() throws Exception {
        when(advisor.enabled()).thenReturn(true);
        when(advisor.advise(any())).thenReturn(Optional.empty());
        String[] pf = projectAndFinding();

        mvc.perform(post("/api/projects/" + pf[0] + "/advice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingId\":\"nope\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/projects/" + pf[0] + "/advice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"findingId\":\"" + pf[1] + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("llm_unavailable"));
    }
}
