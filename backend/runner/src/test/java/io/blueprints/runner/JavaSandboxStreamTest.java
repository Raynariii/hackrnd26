package io.blueprints.runner;

import io.blueprints.runner.RunModels.CompileResult;
import io.blueprints.runner.RunModels.RunnerRequest;
import io.blueprints.runner.RunModels.SourceFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Потоковый запуск для консоли (SSE): вывод приходит по мере появления, а не после завершения программы. */
@SpringBootTest
@AutoConfigureMockMvc
class JavaSandboxStreamTest {

    private final JavaSandbox sandbox = new JavaSandbox(1 << 20, 60_000, "128m");

    record Event(String name, Object data, long atMs) {
    }

    @Autowired
    MockMvc mvc;

    private List<Event> collect(String src, String stdin, int timeoutMs) throws Exception {
        List<Event> events = new ArrayList<>();
        long start = System.nanoTime();
        sandbox.runStreaming(new RunnerRequest(List.of(new SourceFile("Main.java", src)), null, stdin, timeoutMs),
                (name, data) -> {
                    synchronized (events) {
                        events.add(new Event(name, data, (System.nanoTime() - start) / 1_000_000));
                    }
                });
        return events;
    }

    private static String text(List<Event> events, String name) {
        StringBuilder sb = new StringBuilder();
        for (Event e : events) {
            if (e.name().equals(name)) {
                sb.append(((Map<?, ?>) e.data()).get("text"));
            }
        }
        return sb.toString();
    }

    @Test
    void outputArrivesBeforeTheProgramEnds() throws Exception {
        String src = """
                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println("первая " + new java.util.Scanner(System.in).nextLine());
                        System.out.flush();
                        Thread.sleep(600);
                        System.out.println("вторая");
                        System.err.println("ошибка");
                    }
                }
                """;
        List<Event> events = collect(src, "строка", 20_000);

        assertThat(events.get(0).name()).isEqualTo("compile");
        assertThat(((CompileResult) events.get(0).data()).ok()).isTrue();
        Event last = events.get(events.size() - 1);
        assertThat(last.name()).isEqualTo("exit");
        assertThat(((JavaSandbox.StreamExit) last.data()).exitCode()).isZero();
        assertThat(text(events, "stdout")).isEqualTo("первая строка\nвторая\n");
        assertThat(text(events, "stderr")).isEqualTo("ошибка\n");
        Event first = events.stream().filter(e -> e.name().equals("stdout")).findFirst().orElseThrow();
        assertThat(last.atMs() - first.atMs()).as("первая строка пришла до конца программы").isGreaterThan(400);
    }

    @Test
    void compileErrorAndTimeout() throws Exception {
        List<Event> broken = collect("public class Main { void f() { int x = ; } }", "", 20_000);
        assertThat(broken).hasSize(1);
        assertThat(((CompileResult) broken.get(0).data()).ok()).isFalse();

        List<Event> hang = collect("public class Main { public static void main(String[] a) { System.out.println(\"старт\"); while (true) {} } }", "", 2_000);
        assertThat(text(hang, "stdout")).isEqualTo("старт\n");
        assertThat(((JavaSandbox.StreamExit) hang.get(hang.size() - 1).data()).timedOut()).isTrue();
    }

    @Test
    void endpointStreamsServerSentEvents() throws Exception {
        String body = """
                {"files":[{"path":"Main.java","content":"public class Main { public static void main(String[] a) { System.out.println(\\"привет\\"); } }"}],
                 "stdin":"", "timeoutMs": 20000}
                """;
        MvcResult started = mvc.perform(post("/internal/run-stream").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        String sse = mvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(sse).startsWith("event: compile\ndata: {\"ok\":true")
                .contains("event: stdout\ndata: {\"text\":\"привет\\n\"}")
                .contains("event: exit\ndata: {\"exitCode\":0");
    }

    @Test
    void badMainClassGivesErrorEvent() throws Exception {
        String body = """
                {"files":[{"path":"Main.java","content":"public class Main {}"}], "mainClass":"-version"}
                """;
        MvcResult started = mvc.perform(post("/internal/run-stream").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        String sse = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(sse).startsWith("event: error\ndata: {\"message\":\"Некорректное имя mainClass");
    }
}
