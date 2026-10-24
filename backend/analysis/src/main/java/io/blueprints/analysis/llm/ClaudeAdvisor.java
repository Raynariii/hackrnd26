package io.blueprints.analysis.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * LLM-советчик через Claude Messages API — дополнение к детерминированным правилам (D6).
 *
 * <p>Ключ берётся только из переменной окружения {@code ANTHROPIC_API_KEY}: в git и в логи он не попадает,
 * передаётся заголовком {@code x-api-key}. Модель — {@code ANTHROPIC_MODEL} (по умолчанию
 * {@value #DEFAULT_MODEL}), адрес — {@code BLUEPRINTS_LLM_URL} (для тестового прокси).
 * Без ключа {@link #enabled()} = {@code false}: модуль молча выключен, сетевых вызовов нет.
 *
 * <p>Совет — только текст. Применять его можно лишь после quality gate (сборка + дифф-тесты),
 * поэтому советчик не меняет код и не участвует в авто-исправлениях.
 */
public final class ClaudeAdvisor implements OptimizationAdvisor {

    static final String DEFAULT_URL = "https://api.anthropic.com/v1/messages";
    static final String DEFAULT_MODEL = "claude-3-5-sonnet-latest";
    static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final int MAX_TOKENS = 700;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final System.Logger LOG = System.getLogger(ClaudeAdvisor.class.getName());

    private static final String SYSTEM = """
            Ты помогаешь оптимизировать Java-код в инструменте анализа монолитов.
            Отвечай по-русски, кратко и по делу. Предлагай минимальную правку, которая сохраняет поведение
            (stdout, stderr, код выхода), не меняет публичные сигнатуры и не добавляет зависимостей.
            Если безопасной автоматической правки нет, так и скажи.
            """;

    private final String apiKey;
    private final String model;
    private final String url;
    private final LlmTransport transport;

    /** Советчик из окружения; без {@code ANTHROPIC_API_KEY} остаётся выключенным. */
    public static ClaudeAdvisor fromEnvironment() {
        return new ClaudeAdvisor(
                System.getenv("ANTHROPIC_API_KEY"),
                environment("ANTHROPIC_MODEL", DEFAULT_MODEL),
                environment("BLUEPRINTS_LLM_URL", DEFAULT_URL),
                new HttpLlmTransport());
    }

    ClaudeAdvisor(String apiKey, String model, String url, LlmTransport transport) {
        this.apiKey = apiKey;
        this.model = model;
        this.url = url;
        this.transport = transport;
    }

    @Override
    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Optional<Advice> advise(AdviceRequest request) {
        if (!enabled() || request == null) {
            return Optional.empty();
        }
        try {
            LlmTransport.Response response = transport.send(
                    new LlmTransport.Request(url, headers(), requestBody(request)));
            if (response.status() != 200) {
                LOG.log(System.Logger.Level.DEBUG, "LLM ответил статусом {0}", response.status());
                return Optional.empty();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(System.Logger.Level.DEBUG, "LLM-запрос прерван");
            return Optional.empty();
        } catch (Exception e) {
            // Советчик — необязательная функция: сеть и формат ответа не должны ронять диагностику.
            LOG.log(System.Logger.Level.DEBUG, "LLM-запрос не удался: {0}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json");
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        headers.put("x-api-key", apiKey);
        return headers;
    }

    private String requestBody(AdviceRequest request) throws JsonProcessingException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);
        root.put("temperature", 0.0);
        root.put("system", SYSTEM);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt(request));
        return MAPPER.writeValueAsString(root);
    }

    private static String prompt(AdviceRequest request) {
        return """
                Находка анализатора: %s — %s
                Файл: %s
                Объяснение: %s

                Код:
                ```java
                %s
                ```

                Предложи минимальную оптимизацию, сохраняющую поведение. Формат ответа:
                2–3 предложения объяснения, затем полный исправленный фрагмент в блоке ```java.
                """.formatted(text(request.ruleId()), text(request.title()), text(request.file()),
                text(request.explanation()), text(request.code()));
    }

    private static Optional<Advice> parse(String body) throws JsonProcessingException {
        JsonNode root = MAPPER.readTree(body);
        JsonNode content = root.path("content");
        if (!content.isArray()) {
            return Optional.empty();
        }
        StringBuilder suggestion = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                suggestion.append(block.path("text").asText(""));
            }
        }
        String text = suggestion.toString().trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Advice(text, root.path("model").asText("")));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
