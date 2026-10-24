package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Инвариант, которого не хватало UI: {@code Fix.autoApplicable} обязан совпадать с тем, что реально
 * сделает {@code POST /fixes}. Запрос роли 4: в диагностике находка была «применима», кнопка в UI
 * активна, а сервер отвечал 422.
 *
 * <p>Обе стороны теперь считают применимость через {@link FixSafety#explain(String,
 * com.github.javaparser.ast.Node)}, поэтому тест держит их вместе: применимая находка применяется,
 * неприменимая — отбивается тем же текстом, который показан в описании фикса.
 */
class FixApplicabilityTest {

    private static final Set<String> SUPPORTED_RULES = Set.of(
            "collection-lookup-in-loop", "string-concat-in-loop", "linked-list-index-access", "magic-number");

    private static List<SourceFile> fixtureFiles() throws Exception {
        try (InputStream stream = FixApplicabilityTest.class.getResourceAsStream("/monolith/OrdersMonolith.java")) {
            return List.of(new SourceFile("OrdersMonolith.java",
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    private static List<Finding> supportedFindings(List<SourceFile> files) {
        ProjectModel model = new JavaProjectParser().parse(files);
        return Analyzer.withDefaultRules().analyze(model).findings().stream()
                .filter(finding -> finding.fix() != null && SUPPORTED_RULES.contains(finding.rule()))
                .toList();
    }

    @Test
    void everyAutoApplicableFindingIsReallyApplied() throws Exception {
        List<SourceFile> files = fixtureFiles();
        List<Finding> findings = supportedFindings(files);

        assertThat(findings).anyMatch(finding -> finding.fix().autoApplicable());
        assertThat(findings).anyMatch(finding -> !finding.fix().autoApplicable());
        for (Finding finding : findings) {
            if (!finding.fix().autoApplicable()) {
                continue;
            }
            assertThatCode(() -> new FixEngine().apply(files, finding.id()))
                    .as("находка %s помечена применимой — POST /fixes обязан её применить", finding.id())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectedFindingExplainsItselfWithSameText() throws Exception {
        List<SourceFile> files = fixtureFiles();
        List<Finding> rejected = supportedFindings(files).stream()
                .filter(finding -> !finding.fix().autoApplicable())
                .toList();

        assertThat(rejected).isNotEmpty();
        for (Finding finding : rejected) {
            assertThatThrownBy(() -> new FixEngine().apply(files, finding.id()))
                    .as("находка %s помечена неприменимой — POST /fixes обязан отказать", finding.id())
                    .isInstanceOf(FixNotApplicableException.class)
                    .hasMessage(finding.fix().description());
        }
    }

    @Test
    void mutatedCollectionMakesFixManualOnly() throws Exception {
        List<SourceFile> files = fixtureFiles();
        List<Finding> lookups = supportedFindings(files).stream()
                .filter(finding -> finding.rule().equals("collection-lookup-in-loop"))
                .toList();

        assertThat(lookups).hasSize(2);
        Finding manual = lookups.stream().filter(finding -> !finding.fix().autoApplicable()).findFirst().orElseThrow();
        Finding auto = lookups.stream().filter(finding -> finding.fix().autoApplicable()).findFirst().orElseThrow();

        assertThat(manual.fix().description()).contains("меняется внутри цикла");
        assertThat(auto.fix().description()).doesNotContain("меняется");
    }
}
