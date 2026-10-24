package io.blueprints.analysis;

import io.blueprints.analysis.fixes.Fixer;
import io.blueprints.analysis.fixes.Fixers;
import io.blueprints.analysis.rules.Rules;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Контракт «диагноз проверяемый» на стыке диагностики и авто-исправлений: {@code Fix.autoApplicable}
 * в диагностике — это ровно то, что {@code FixEngine#apply} сделает на этой же версии кода.
 *
 * <p>Запрос роли 4: если правило предлагает исправление, а движок на нём отказывает (коллекция
 * меняется в цикле, строку читают внутри цикла), кнопка «Применить» в UI обманывает пользователя.
 * Тест держит флаг и движок согласованными, а реестры — единственным местом регистрации.
 */
class AnalyzerFixApplicabilityTest {

    private static ProjectModel model(String source) {
        return new JavaProjectParser().parse(List.of(new SourceFile("Main.java", source)));
    }

    private static AnalysisReport analyze(String source) {
        return Analyzer.withDefaultRules().analyze(model(source));
    }

    private static Finding finding(String source, String rule) {
        return analyze(source).findings().stream()
                .filter(candidate -> candidate.rule().equals(rule))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет находки правила " + rule));
    }

    @Test
    void applicableFlagMatchesWhatFixEngineDoes() {
        String source = """
                class A {
                    boolean f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            if (allowed.contains(name)) {
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """;

        Finding found = finding(source, "collection-lookup-in-loop");

        assertThat(found.fix().autoApplicable()).isTrue();
        assertThatCode(() -> new FixEngine().apply(List.of(new SourceFile("Main.java", source)), found.id()))
                .doesNotThrowAnyException();
    }

    @Test
    void flagIsFalseWhenCollectionChangesInsideLoop() {
        String source = """
                class A {
                    boolean f(java.util.List<String> names, java.util.List<String> allowed) {
                        for (String name : names) {
                            if (allowed.contains(name)) {
                                allowed.remove(name);
                                return true;
                            }
                        }
                        return false;
                    }
                }
                """;

        Finding found = finding(source, "collection-lookup-in-loop");

        assertThat(found.fix().autoApplicable()).isFalse();
        assertThat(found.fix().description()).isNotBlank();
    }

    @Test
    void flagIsFalseWhenStringIsReadInsideLoop() {
        String source = """
                class A {
                    void f(java.util.List<String> items) {
                        String result = "";
                        for (String item : items) {
                            result += item;
                            System.out.println(result);
                        }
                    }
                }
                """;

        Finding found = finding(source, "string-concat-in-loop");

        assertThat(found.fix().autoApplicable()).isFalse();
    }

    @Test
    void everyApplicableFindingCanBeAppliedWithoutChanges() {
        String source = """
                class A {
                    java.util.List<String> f(java.util.List<String> items, java.util.List<String> allowed) {
                        java.util.List<String> result = new java.util.ArrayList<>();
                        String report = "";
                        for (String item : items) {
                            if (allowed.contains(item)) {
                                result.add(item);
                            }
                            report += item;
                        }
                        if (items.size() > 100) {
                            result.add(report);
                        }
                        return result;
                    }
                }
                """;

        List<Finding> applicable = analyze(source).findings().stream()
                .filter(finding -> finding.fix() != null && finding.fix().autoApplicable())
                .toList();

        assertThat(applicable).isNotEmpty();
        for (Finding finding : applicable) {
            assertThatCode(() -> new FixEngine().apply(List.of(new SourceFile("Main.java", source)), finding.id()))
                    .as("находка %s", finding.id())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void registriesAreTheSinglePlaceOfRegistration() {
        assertThat(Rules.defaultRules()).extracting(Rule::id).doesNotHaveDuplicates().hasSize(14);
        assertThat(Analyzer.defaultRules()).extracting(Rule::id)
                .containsExactlyElementsOf(Rules.defaultRules().stream().map(Rule::id).toList());
        assertThat(Fixers.all()).extracting(Fixer::kind)
                .containsExactlyInAnyOrder("index-by-key", "string-builder", "extract-constant", "iterator", "nested-loop-index");
    }
}
