package io.blueprints.api.verify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputNormalizerTest {

    @Test
    void replacesIdentityHashesAndLineEndings() {
        assertThat(OutputNormalizer.normalize("Order@1b6d3586\r\n[I@4554617c\r\n"))
                .isEqualTo("Order@HASH\n[I@HASH\n");
    }

    @Test
    void lambdaClassNamesDoNotDependOnRun() {
        assertThat(OutputNormalizer.normalize("Main$PriceRule$$Lambda/0x00007f82d0003128@6d06d69c"))
                .isEqualTo(OutputNormalizer.normalize("Main$PriceRule$$Lambda/0x00007fa6a0003128@7852e922"))
                .isEqualTo("Main$PriceRule$$Lambda@HASH");
        assertThat(OutputNormalizer.normalize("Main$$Lambda$14/0x0000000800c03000@1b6d3586"))
                .isEqualTo("Main$$Lambda@HASH");
    }

    @Test
    void dropsJvmNoiseAndSandboxPaths() {
        String stderr = "Picked up JAVA_TOOL_OPTIONS: -Dfoo=bar\n/tmp/run-123456/src/Main.java not found\n";
        assertThat(OutputNormalizer.normalize(stderr)).isEqualTo("Main.java not found\n");
    }

    @Test
    void stderrIgnoresStackFramesButKeepsExceptionAndCause() {
        String before = """
                Exception in thread "main" java.lang.IllegalStateException: пусто
                \tat Main.process(Main.java:42)
                \tat Main.main(Main.java:10)
                Caused by: java.util.NoSuchElementException
                \t... 2 more
                """;
        String after = """
                Exception in thread "main" java.lang.IllegalStateException: пусто
                \tat Main.extracted(Main.java:57)
                \tat Main.process(Main.java:45)
                \tat Main.main(Main.java:10)
                Caused by: java.util.NoSuchElementException
                \t... 3 more
                """;
        assertThat(OutputNormalizer.normalizeStderr(after)).isEqualTo(OutputNormalizer.normalizeStderr(before))
                .contains("IllegalStateException: пусто").contains("Caused by: java.util.NoSuchElementException");
    }

    @Test
    void differentExceptionMessagesStayDifferent() {
        assertThat(OutputNormalizer.normalizeStderr("java.lang.RuntimeException: a\n\tat X.y(X.java:1)"))
                .isNotEqualTo(OutputNormalizer.normalizeStderr("java.lang.RuntimeException: b\n\tat X.y(X.java:1)"));
    }
}
