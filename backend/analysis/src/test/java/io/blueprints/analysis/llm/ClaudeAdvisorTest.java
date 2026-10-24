package io.blueprints.analysis.llm;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Тесты LLM-советчика без сети: транспорт подменяется лямбдой, ключ — только в конструкторе.
 * Проверяем главное для безопасности и демо: без ключа запросов нет, ключ не попадает в тело
 * запроса, а любой сбой сети/формата не роняет диагностику.
 */
class ClaudeAdvisorTest {

    private static final AdviceRequest REQUEST = new AdviceRequest(
            "collection-lookup-in-loop",
            "Поиск по списку внутри цикла — O(n²)",
            "contains перебирает элементы списка на каждой итерации",
            "Main.java",
            "class A { boolean f(java.util.List<String> xs) { return xs.contains(\"a\"); } }");

    @Test
    void disabledWithoutApiKeyAndMakesNoCalls() {
        AtomicInteger calls = new AtomicInteger();
        ClaudeAdvisor advisor = new ClaudeAdvisor("   ", "claude-test", "http://localhost/unused",
                request -> {
                    calls.incrementAndGet();
                    return new LlmTransport.Response(200, "{}");
                });

        assertThat(advisor.enabled()).isFalse();
        assertThat(advisor.advise(REQUEST)).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void sendsKeyInHeaderAndParsesSuggestion() {
        AtomicReference<LlmTransport.Request> sent = new AtomicReference<>();
        ClaudeAdvisor advisor = new ClaudeAdvisor("secret-key", "claude-test", "http://localhost/v1/messages",
                request -> {
                    sent.set(request);
                    return new LlmTransport.Response(200, """
                            {"model":"claude-test","content":[{"type":"text","text":"Замените список на HashSet."}]}
                            """);
                });

        Optional<Advice> advice = advisor.advise(REQUEST);

        assertThat(advice).isPresent();
        assertThat(advice.orElseThrow().suggestion()).contains("HashSet");
        assertThat(advice.orElseThrow().model()).isEqualTo("claude-test");
        assertThat(sent.get().url()).isEqualTo("http://localhost/v1/messages");
        assertThat(sent.get().headers()).containsEntry("x-api-key", "secret-key");
        assertThat(sent.get().body())
                .contains("claude-test")
                .contains("collection-lookup-in-loop")
                .contains("class A { boolean f")
                .doesNotContain("secret-key");
    }

    @Test
    void returnsEmptyOnHttpError() {
        ClaudeAdvisor advisor = new ClaudeAdvisor("secret-key", "claude-test", "http://localhost",
                request -> new LlmTransport.Response(429, "{\"error\":\"rate_limit\"}"));

        assertThat(advisor.advise(REQUEST)).isEmpty();
    }

    @Test
    void returnsEmptyOnBrokenResponse() {
        ClaudeAdvisor advisor = new ClaudeAdvisor("secret-key", "claude-test", "http://localhost",
                request -> new LlmTransport.Response(200, "not json"));

        assertThat(advisor.advise(REQUEST)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTransportFails() {
        ClaudeAdvisor advisor = new ClaudeAdvisor("secret-key", "claude-test", "http://localhost",
                request -> {
                    throw new java.io.IOException("нет сети");
                });

        assertThat(advisor.advise(REQUEST)).isEmpty();
    }

    @Test
    void fromEnvironmentDoesNotFailWithoutKey() {
        assertThatCode(ClaudeAdvisor::fromEnvironment).doesNotThrowAnyException();
    }
}
