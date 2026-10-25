package io.blueprints.core;

import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.Graph;
import io.blueprints.core.graph.GraphNode;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.model.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Разделы, описания и точка входа на обзоре: по ним граф монолита читается без исходника. */
class OverviewNotesTest {

    private static final String SOURCE = """
            import java.util.*;

            public class Shop {
                // ------------------------------------------------------------------
                // Глобальное состояние
                // ------------------------------------------------------------------

                static Map<String, Integer> stock = new HashMap<>();

                /** Сколько заказов обработано. */
                static int processed;

                // ------------------------------------------------------------------
                // Склад
                // ------------------------------------------------------------------

                /**
                 * Добавляет товар на склад. Количество суммируется.
                 */
                static void add(String item, int qty) {
                    // ----- не баннер: внутри метода -----
                    stock.merge(item, qty, Integer::sum);
                }

                // проверка остатка перед продажей
                static boolean has(String item) {
                    return stock.getOrDefault(item, 0) > 0;
                }

                // ===== Ввод и вывод =====

                public static void main(String[] args) {
                    add("a", 1);
                    System.out.println(has("a"));
                }
            }
            """;

    private static GraphNode node(Graph g, String label) {
        return g.nodes().stream().filter(n -> n.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void membersCarrySectionDocAndEntryPoint() {
        Graph g = new DefaultGraphBuilder().overview(new JavaProjectParser().parse(List.of(new SourceFile("Shop.java", SOURCE))));

        assertThat(node(g, "stock").meta()).containsEntry("section", "Глобальное состояние").doesNotContainKey("doc");
        assertThat(node(g, "stock").code()).isEqualTo("static Map<String, Integer> stock = new HashMap<>();");
        assertThat(node(g, "processed").meta()).containsEntry("doc", "Сколько заказов обработано.");
        assertThat(node(g, "add").meta())
                .containsEntry("section", "Склад")
                .containsEntry("doc", "Добавляет товар на склад.");
        assertThat(node(g, "has").meta())
                .containsEntry("section", "Склад")
                .containsEntry("doc", "проверка остатка перед продажей");
        assertThat(node(g, "main").meta()).containsEntry("section", "Ввод и вывод").containsEntry("entry", true);
        assertThat(node(g, "add").meta()).doesNotContainKey("entry");
        assertThat(node(g, "Shop").meta()).doesNotContainKey("section");
    }
}
