package io.blueprints.runner;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Контекст Spring поднимается, а /internal/run-batch отвечает по контракту RunBatchResult. */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void runBatchEndpoint() throws Exception {
        String body = """
                {"files":[{"path":"Main.java","content":"public class Main { public static void main(String[] a) { System.out.print(new java.util.Scanner(System.in).nextLine().length()); } }"}],
                 "inputs":["абв","hello"], "timeoutMs": 20000}
                """;
        mvc.perform(post("/internal/run-batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compile.ok").value(true))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].stdout").value("3"))
                .andExpect(jsonPath("$.results[1].stdout").value("5"));
    }
}
