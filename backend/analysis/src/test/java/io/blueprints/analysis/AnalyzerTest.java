package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerTest {

    @Test
    void flagsLongMethod() {
        String body = "        x++;\n".repeat(50);
        String source = "class A {\n    void f() {\n        int x = 0;\n" + body + "    }\n}\n";
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("A.java", source)));

        AnalysisReport report = Analyzer.withDefaultRules().analyze(model);

        assertThat(report.findings()).extracting(Finding::rule).containsExactly("long-method");
        assertThat(report.findings().get(0).startLine()).isEqualTo(2);
        assertThat(report.metrics()).hasSize(1);
    }
}
