import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Мини-монолит для тестов анализатора: в нём намеренно заложены дефекты из PLAN.md §4.
 * Служит фикстурой «панель Проблемы» и проверяет, что правила не дают ложных срабатываний
 * на обычном коде вокруг дефектов.
 */
public class OrdersMonolith {

    private static final int PAGE_SIZE = 50;
    private int processed;

    public static void main(String[] args) {
        OrdersMonolith app = new OrdersMonolith();
        app.run();
    }

    void run() {
        List<String> orders = new ArrayList<>();
        orders.add("A-1");
        orders.add("B-2");
        String report = processOrders(orders);
        printSummary(report);
        System.out.println(duplicateCheck(orders));
    }

    void printSummary(String report) {
        processed++;
        System.out.println("Обработано: " + report.length());
    }

    /** O(n²): вложенные циклы по одной коллекции плюс contains внутри них. */
    List<String> duplicateCheck(List<String> orders) {
        List<String> duplicates = new ArrayList<>();
        for (String left : orders) {
            for (String right : orders) {
                if (!left.equals(right)) {
                    continue;
                }
                if (!duplicates.contains(left)) {
                    duplicates.add(left);
                }
            }
        }
        return duplicates;
    }

    /** O(n²): contains по списку и конкатенация строк в одном цикле. */
    String processOrders(List<String> orders) {
        String report = "";
        int total = 0;
        for (String order : orders) {
            if (orders.contains(order)) {
                total++;
            }
            report += order + ";";
        }
        if (total > 100) {
            System.out.println(report);
        }
        return report;
    }

    /** O(n²): LinkedList.get(i) в цикле по индексу. */
    void scanQueue(LinkedList<String> queue) {
        for (int i = 0; i < queue.size(); i++) {
            System.out.println(queue.get(i));
        }
    }

    /** Пустой catch: ошибка разбора проглатывается. */
    int parseAmounts(List<String> rows) {
        int sum = 0;
        for (String row : rows) {
            try {
                sum += Integer.parseInt(row);
            } catch (NumberFormatException e) {
            }
        }
        return sum;
    }

    /** Глубокая вложенность: пять уровней if. */
    void classify(boolean a, boolean b, boolean c, boolean d, boolean e) {
        if (a) {
            if (b) {
                if (c) {
                    if (d) {
                        if (e) {
                            System.out.println("all");
                        }
                    }
                }
            }
        }
    }

    int sumLengths(List<String> rows) {
        int total = 0;
        for (String row : rows) {
            total += row.length();
        }
        total = total * 2;
        total = total + 10;
        System.out.println(total);
        return total;
    }

    int sumLengthsAgain(List<String> rows) {
        int total = 0;
        for (String row : rows) {
            total += row.length();
        }
        total = total * 2;
        total = total + 10;
        System.out.println(total);
        return total;
    }
}
