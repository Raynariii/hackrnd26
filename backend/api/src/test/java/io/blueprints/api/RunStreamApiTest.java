package io.blueprints.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.blueprints.api.runner.RunnerClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /run/stream: поток событий песочницы пробрасывается как есть, stdin запоминается для verify. */
@SpringBootTest
@AutoConfigureMockMvc
class RunStreamApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    RunnerClient runner;

    @Test
    void streamsRunnerEventsAndRemembersStdin() throws Exception {
        String sse = "event: compile\ndata: {\"ok\":true}\n\nevent: stdout\ndata: {\"text\":\"привет\\n\"}\n\n"
                + "event: exit\ndata: {\"exitCode\":0,\"timeMs\":5,\"timedOut\":false}\n\n";
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(3);
            out.write(sse.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(runner).stream(anyList(), eq("7 8"), anyInt(), any());

        String body = json.writeValueAsString(Map.of("files", List.of(Map.of("path", "Main.java",
                "content", "public class Main { public static void main(String[] a) { System.out.println(1); } }"))));
        String id = json.readTree(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        MvcResult started = mvc.perform(post("/api/projects/" + id + "/run/stream")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stdin\":\"7 8\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        String response = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/event-stream")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response).isEqualTo(sse);
    }
}
