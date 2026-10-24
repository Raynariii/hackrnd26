package io.blueprints.analysis;

import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Тесты предложения по декомпозиции монолита. */
class DecomposerTest {

    private static Decomposer.Proposal propose(String source) {
        ProjectModel model = new JavaProjectParser().parse(List.of(new SourceFile("Monolith.java", source)));
        return new Decomposer().propose(model);
    }

    @Test
    void splitsMonolithByResponsibility() {
        String source = """
                class Monolith {
                    private static int counter;

                    void readFile(String path) {
                        counter++;
                    }

                    String parseLine(String line) {
                        return line.trim();
                    }

                    void loadInput() {
                        counter++;
                    }

                    int analyzeOrders(java.util.List<String> orders) {
                        return orders.size();
                    }

                    boolean validateOrder(String order) {
                        return order != null;
                    }

                    void reportStats() {
                        System.out.println(counter);
                    }

                    void sortOrders(java.util.List<String> orders) {
                        orders.sort(null);
                    }

                    void deduplicate(java.util.List<String> orders) {
                        System.out.println(orders);
                    }

                    static void main(String[] args) {
                        new Monolith().run();
                    }

                    void run() {
                        loadInput();
                    }
                }
                """;

        Decomposer.Proposal proposal = propose(source);

        assertThat(proposal.proposal()).extracting(Decomposer.ProposedClass::className)
                .containsExactly("Parser", "Analyzer", "Optimizer", "Main");
        assertThat(members(proposal, "Parser")).contains("readFile(String)", "parseLine(String)", "loadInput()");
        assertThat(members(proposal, "Analyzer"))
                .contains("analyzeOrders(List)", "validateOrder(String)", "reportStats()");
        assertThat(members(proposal, "Optimizer")).contains("sortOrders(List)", "deduplicate(List)");
        assertThat(members(proposal, "Main")).contains("main(String[])", "run()");
        assertThat(proposal.notes()).anySatisfy(note -> assertThat(note).contains("→ 4 класса"));
    }

    @Test
    void keepsFieldsUsedByMovedMethods() {
        String source = """
                class Monolith {
                    private static int counter;

                    void analyzeOrders(java.util.List<String> orders) {
                        counter++;
                    }

                    void validateOrder(String order) {
                        counter++;
                    }

                    void reportStats() {
                        System.out.println(counter);
                    }

                    void readFile(String path) {
                        counter++;
                    }

                    void parseLine(String line) {
                        counter++;
                    }

                    void run() {
                        readFile("a");
                    }
                }
                """;

        Decomposer.Proposal proposal = propose(source);

        assertThat(members(proposal, "Analyzer")).contains("field counter");
    }

    @Test
    void reportsPartitionCohesionForJury() {
        String source = """
                class Monolith {
                    void readFile(String path) {
                        parseLine(path);
                    }

                    String parseLine(String line) {
                        return line;
                    }

                    void loadInput() {
                        readFile("a");
                    }

                    void analyzeOrders(java.util.List<String> orders) {
                        validateOrder("a");
                    }

                    boolean validateOrder(String order) {
                        return order != null;
                    }

                    void reportStats() {
                        System.out.println(validateOrder("x"));
                    }
                }
                """;

        Decomposer.Proposal proposal = propose(source);

        assertThat(proposal.notes()).anySatisfy(note -> assertThat(note)
                .contains("Сцепление")
                .contains("внутри групп")
                .contains("между группами"));
    }

    @Test
    void ignoresSmallClass() {
        String source = """
                class Small {
                    void readFile(String path) {
                    }

                    void parseLine(String line) {
                    }
                }
                """;

        assertThat(propose(source).proposal()).isEmpty();
        assertThat(propose(source).notes()).isEmpty();
    }

    @Test
    void ignoresClassWithoutClearResponsibilities() {
        String source = """
                class Opaque {
                    void step1() { }
                    void step2() { }
                    void step3() { }
                    void step4() { }
                    void step5() { }
                    void step6() { }
                    void step7() { }
                    void step8() { }
                }
                """;

        assertThat(propose(source).proposal()).isEmpty();
    }

    private static List<String> members(Decomposer.Proposal proposal, String className) {
        return proposal.proposal().stream()
                .filter(proposed -> proposed.className().equals(className))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет класса " + className))
                .members();
    }
}
