package io.blueprints.api.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Нормализация вывода перед сравнением в verify (contracts/openapi.yaml → VerifyCase), чтобы не было ложных
 * расхождений: identity-хеши, переводы строк, пути песочницы, служебные строки JVM, номера строк в стектрейсах.
 */
public final class OutputNormalizer {

    /** {@code Object.toString()} по умолчанию: {@code Order@1b6d3586} → {@code Order@HASH}. */
    private static final Pattern IDENTITY_HASH = Pattern.compile("([\\w$\\]\\[;])@[0-9a-f]{4,8}\\b");
    /**
     * Скрытый класс лямбды: {@code Main$$Lambda/0x00007f82d0003128} (JDK 21) или {@code Main$$Lambda$14/0x0000000800c03000}
     * — адрес и номер меняются от запуска к запуску и от правки к правке.
     */
    private static final Pattern LAMBDA_CLASS = Pattern.compile("\\$\\$Lambda(?:\\$\\d+)?(?:/0x[0-9a-f]+)?");
    private static final Pattern JVM_NOISE = Pattern.compile(
            "(?m)^(?:Picked up (?:JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS):.*|NOTE: Picked up .*)\\n?");
    private static final Pattern SANDBOX_PATH = Pattern.compile("(?:/[\\w.\\-]+)*/run-\\d+(?:/(?:src|out))?/?|<sandbox>/?");
    /** Кадры стектрейса и хвост «... 3 more» — зависят от номеров строк и структуры методов. */
    private static final Pattern STACK_FRAME = Pattern.compile("^\\s+(?:at\\s.*|\\.\\.\\.\\s*\\d+\\s+(?:more|common frames omitted))$");

    private OutputNormalizer() {
    }

    /** Общая нормализация stdout и stderr. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        s = JVM_NOISE.matcher(s).replaceAll("");
        s = SANDBOX_PATH.matcher(s).replaceAll("");
        s = LAMBDA_CLASS.matcher(s).replaceAll("\\$\\$Lambda");
        s = IDENTITY_HASH.matcher(s).replaceAll("$1@HASH");
        return s;
    }

    /**
     * stderr для сравнения: без кадров стектрейса. Остаются строки, которые программа печатала сама, и строки
     * исключений (тип + сообщение, «Caused by: …») — именно они и описывают поведение.
     */
    public static String normalizeStderr(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : normalize(text).split("\n", -1)) {
            if (!STACK_FRAME.matcher(line).matches()) {
                kept.add(line);
            }
        }
        return String.join("\n", kept).strip();
    }

    public static String normalizeStdout(String text) {
        return normalize(text);
    }
}
