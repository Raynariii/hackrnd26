package io.blueprints.api.diff;

import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffTest {

    @Test
    void localChangeGivesOneHunkWithContext() {
        String before = "a\nb\nc\nd\ne\nf\ng\nh\ni\n";
        String after = "a\nb\nc\nd\nE\nf\ng\nh\ni\n";
        String diff = UnifiedDiff.of(List.of(new SourceFile("Main.java", before)), List.of(new SourceFile("Main.java", after)));

        assertThat(diff).isEqualTo("""
                --- a/Main.java
                +++ b/Main.java
                @@ -2,7 +2,7 @@
                 b
                 c
                 d
                -e
                +E
                 f
                 g
                 h
                """);
    }

    @Test
    void insertionsAndNewFiles() {
        String diff = UnifiedDiff.of(
                List.of(new SourceFile("Main.java", "x\ny\n")),
                List.of(new SourceFile("Main.java", "x\nnew\ny\n"), new SourceFile("Util.java", "class Util {}\n")));

        assertThat(diff).contains("@@ -1,2 +1,3 @@\n x\n+new\n y\n")
                .contains("--- /dev/null\n+++ b/Util.java\n@@ -0,0 +1 @@\n+class Util {}\n");
    }

    @Test
    void identicalProjectsHaveEmptyDiff() {
        List<SourceFile> files = List.of(new SourceFile("Main.java", "class Main {}\n"));
        assertThat(UnifiedDiff.of(files, files)).isEmpty();
    }
}
