package io.blueprints.analysis.llm;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP-транспорт советчика. Вынесен отдельно, чтобы тесты проверяли запрос и разбор ответа
 * без сети и без ключа.
 */
interface LlmTransport {

    Response send(Request request) throws IOException, InterruptedException;

    record Request(String url, Map<String, String> headers, String body) {
    }

    record Response(int status, String body) {
    }
}
