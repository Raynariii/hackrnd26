import java.util.*;

public class Main {
    static List<Integer> processOrders(List<Integer> orders) {
        List<Integer> result = new ArrayList<>();
        if (orders == null || orders.isEmpty()) {
            return result;
        }
        for (int order : orders) {
            if (!result.contains(order)) {
                result.add(order * 2);
            }
        }
        return result;
    }

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        List<Integer> orders = new ArrayList<>();
        while (in.hasNextInt()) {
            orders.add(in.nextInt());
        }
        System.out.println("Заказы: " + processOrders(orders));
    }
}
