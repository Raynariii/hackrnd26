package io.blueprints.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createProjectAndGetOverviewGraph() throws Exception {
        String body = """
                {"name":"demo","files":[{"path":"Main.java","content":"public class Main { public static void main(String[] a) { System.out.println(1); } }"}]}
                """;
        String response = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/projects/" + id + "/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level").value("overview"))
                .andExpect(jsonPath("$.nodes[0].kind").value("class"))
                .andExpect(jsonPath("$.nodes[1].kind").value("method"));
    }

    @Test
    void rejectsUnparsableCode() throws Exception {
        String body = """
                {"files":[{"path":"Bad.java","content":"class Bad { void f( }"}]}
                """;
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("parse_error"));
    }
}
