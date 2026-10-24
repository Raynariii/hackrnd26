import java.util.*;

/**
 * Монолит «Интернет-магазин»: справочники клиентов и товаров, заказы, скидки, склад, отчёты.
 * Читает команды из stdin (по одной в строке), в конце печатает отчёты.
 *
 * Команды:
 *   CUSTOMER <id> <имя> <город> [VIP]
 *   PRODUCT <sku> <категория> <цена> <остаток> <название...>
 *   ORDER <orderId> <customerId> <sku:qty>[,<sku:qty>...]
 *   CANCEL <orderId>
 *   RESTOCK <sku> <qty>
 *   PROMO <код> <процент>
 *   APPLY <orderId> <код>
 *   REPORT
 */
public class Main {

    // ---------------------------------------------------------------- модели

    interface Priced {
        double price();
    }

    interface Describable {
        String describe();
    }

    static abstract class Entity implements Describable {
        protected final String id;

        Entity(String id) {
            this.id = id;
        }

        String getId() {
            return id;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + id + ")";
        }
    }

    static class Customer extends Entity {
        final String name;
        final String city;
        final boolean vip;
        int ordersCount;
        double totalSpent;

        Customer(String id, String name, String city, boolean vip) {
            super(id);
            this.name = name;
            this.city = city;
            this.vip = vip;
        }

        @Override
        public String describe() {
            return String.format("%s (%s, %s)%s", name, id, city, vip ? " [VIP]" : "");
        }
    }

    static class Product extends Entity implements Priced {
        final String name;
        final String category;
        final double price;
        int stock;
        int sold;

        Product(String sku, String name, String category, double price, int stock) {
            super(sku);
            this.name = name;
            this.category = category;
            this.price = price;
            this.stock = stock;
        }

        @Override
        public double price() {
            return price;
        }

        @Override
        public String describe() {
            return String.format("%s «%s» %.2f руб., остаток %d", id, name, price, stock);
        }
    }

    static class OrderLine implements Priced {
        final Product product;
        final int quantity;

        OrderLine(Product product, int quantity) {
            this.product = product;
            this.quantity = quantity;
        }

        @Override
        public double price() {
            return product.price * quantity;
        }
    }

    static class Order extends Entity implements Priced {
        final Customer customer;
        final List<OrderLine> lines = new ArrayList<>();
        boolean cancelled;
        String promoCode;
        double discountPercent;

        Order(String id, Customer customer) {
            super(id);
            this.customer = customer;
        }

        @Override
        public double price() {
            double sum = 0;
            for (OrderLine line : lines) {
                sum += line.price();
            }
            return sum * (100.0 - discountPercent) / 100.0;
        }

        int itemsCount() {
            int count = 0;
            for (OrderLine line : lines) {
                count += line.quantity;
            }
            return count;
        }

        @Override
        public String describe() {
            return "Заказ " + id + " от " + customer.name + ": " + itemsCount() + " шт. на "
                    + String.format("%.2f", price()) + (cancelled ? " (отменён)" : "");
        }
    }

    // ---------------------------------------------------------------- исключения

    /** Проверяемое исключение: ошибка во входных данных. */
    static class ShopException extends Exception {
        final int lineNumber;

        ShopException(String message, int lineNumber) {
            super(message);
            this.lineNumber = lineNumber;
        }
    }

    static class UnknownEntityException extends ShopException {
        UnknownEntityException(String kind, String id, int lineNumber) {
            super("неизвестный " + kind + " " + id, lineNumber);
        }
    }

    static class OutOfStockException extends ShopException {
        OutOfStockException(Product product, int requested, int lineNumber) {
            super("недостаточно товара " + product.id + ": нужно " + requested + ", есть " + product.stock, lineNumber);
        }
    }

    /** Непроверяемое исключение: нарушение внутреннего инварианта. */
    static class InvariantViolation extends RuntimeException {
        InvariantViolation(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- состояние

    static final Map<String, Customer> customers = new LinkedHashMap<>();
    static final Map<String, Product> products = new LinkedHashMap<>();
    static final List<Order> orders = new ArrayList<>();
    static final Map<String, Double> promoCodes = new HashMap<>();
    static final List<String> errors = new ArrayList<>();
    static final LinkedList<String> auditLog = new LinkedList<>();
    static int processedCommands;
    static int lineNo;

    // ---------------------------------------------------------------- точка входа

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in, "UTF-8");
        boolean reportRequested = false;
        while (in.hasNextLine()) {
            String raw = in.nextLine();
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                if (processCommand(line)) {
                    reportRequested = true;
                }
                processedCommands++;
            } catch (ShopException e) {
                errors.add("строка " + e.lineNumber + ": " + e.getMessage());
            } catch (NumberFormatException e) {
                errors.add("строка " + lineNo + ": не число — " + e.getMessage());
            } finally {
                audit("обработана строка " + lineNo);
            }
        }
        if (reportRequested || !orders.isEmpty()) {
            printReport();
        } else {
            System.out.println("Нет данных для отчёта");
        }
        if (!errors.isEmpty()) {
            System.err.println("Ошибок во входных данных: " + errors.size());
            for (String e : errors) {
                System.err.println("  " + e);
            }
        }
    }

    // ---------------------------------------------------------------- разбор команд

    /** Возвращает true, если запрошен отчёт. Длинный метод с большим switch — заложенный дефект «long-method». */
    static boolean processCommand(String line) throws ShopException {
        String[] parts = line.split("\\s+");
        String command = parts[0].toUpperCase();
        switch (command) {
            case "CUSTOMER": {
                if (parts.length < 4) {
                    throw new ShopException("CUSTOMER: ожидается id, имя и город", lineNo);
                }
                String id = parts[1];
                if (customers.containsKey(id)) {
                    throw new ShopException("клиент " + id + " уже есть", lineNo);
                }
                boolean vip = parts.length > 4 && parts[4].equalsIgnoreCase("VIP");
                customers.put(id, new Customer(id, parts[2], parts[3], vip));
                break;
            }
            case "PRODUCT": {
                if (parts.length < 6) {
                    throw new ShopException("PRODUCT: ожидается sku, категория, цена, остаток и название", lineNo);
                }
                String sku = parts[1];
                double price = Double.parseDouble(parts[3]);
                int stock = Integer.parseInt(parts[4]);
                if (price < 0 || stock < 0) {
                    throw new ShopException("цена и остаток не могут быть отрицательными", lineNo);
                }
                StringBuilder name = new StringBuilder();
                for (int i = 5; i < parts.length; i++) {
                    if (i > 5) {
                        name.append(' ');
                    }
                    name.append(parts[i]);
                }
                products.put(sku, new Product(sku, name.toString(), parts[2], price, stock));
                break;
            }
            case "ORDER": {
                if (parts.length < 4) {
                    throw new ShopException("ORDER: ожидается orderId, customerId и позиции", lineNo);
                }
                Customer customer = customers.get(parts[2]);
                if (customer == null) {
                    throw new UnknownEntityException("клиент", parts[2], lineNo);
                }
                Order order = new Order(parts[1], customer);
                String[] items = parts[3].split(",");
                for (String item : items) {
                    String[] kv = item.split(":");
                    if (kv.length != 2) {
                        throw new ShopException("позиция должна быть sku:количество, а не " + item, lineNo);
                    }
                    Product product = products.get(kv[0]);
                    if (product == null) {
                        throw new UnknownEntityException("товар", kv[0], lineNo);
                    }
                    int qty = Integer.parseInt(kv[1]);
                    if (qty <= 0) {
                        throw new ShopException("количество должно быть положительным: " + item, lineNo);
                    }
                    if (product.stock < qty) {
                        throw new OutOfStockException(product, qty, lineNo);
                    }
                    order.lines.add(new OrderLine(product, qty));
                }
                for (OrderLine l : order.lines) {
                    l.product.stock -= l.quantity;
                    l.product.sold += l.quantity;
                }
                if (customer.vip) {
                    order.discountPercent = 5;
                }
                customer.ordersCount++;
                customer.totalSpent += order.price();
                orders.add(order);
                audit("заказ " + order.id + " на " + String.format("%.2f", order.price()));
                break;
            }
            case "CANCEL": {
                if (parts.length < 2) {
                    throw new ShopException("CANCEL: ожидается orderId", lineNo);
                }
                Order order = findOrder(parts[1]);
                if (order == null) {
                    throw new UnknownEntityException("заказ", parts[1], lineNo);
                }
                if (order.cancelled) {
                    throw new ShopException("заказ " + order.id + " уже отменён", lineNo);
                }
                order.cancelled = true;
                for (OrderLine l : order.lines) {
                    l.product.stock += l.quantity;
                    l.product.sold -= l.quantity;
                }
                order.customer.ordersCount--;
                order.customer.totalSpent -= order.price();
                audit("отмена " + order.id);
                break;
            }
            case "RESTOCK": {
                if (parts.length < 3) {
                    throw new ShopException("RESTOCK: ожидается sku и количество", lineNo);
                }
                Product product = products.get(parts[1]);
                if (product == null) {
                    throw new UnknownEntityException("товар", parts[1], lineNo);
                }
                int qty = safeParse(parts[2], 0);
                if (qty <= 0) {
                    throw new ShopException("RESTOCK: количество должно быть положительным числом", lineNo);
                }
                product.stock += qty;
                audit("пополнение " + product.id + " +" + qty);
                break;
            }
            case "PROMO": {
                if (parts.length < 3) {
                    throw new ShopException("PROMO: ожидается код и процент", lineNo);
                }
                double percent = Double.parseDouble(parts[2]);
                if (percent <= 0 || percent >= 100) {
                    throw new ShopException("процент скидки вне диапазона (0; 100): " + percent, lineNo);
                }
                promoCodes.put(parts[1].toUpperCase(), percent);
                break;
            }
            case "APPLY": {
                if (parts.length < 3) {
                    throw new ShopException("APPLY: ожидается orderId и код", lineNo);
                }
                Order order = findOrder(parts[1]);
                if (order == null) {
                    throw new UnknownEntityException("заказ", parts[1], lineNo);
                }
                Double percent = promoCodes.get(parts[2].toUpperCase());
                if (percent == null) {
                    throw new ShopException("промокод " + parts[2] + " не найден", lineNo);
                }
                double before = order.price();
                order.promoCode = parts[2].toUpperCase();
                order.discountPercent = Math.max(order.discountPercent, percent);
                order.customer.totalSpent += order.price() - before;
                break;
            }
            case "REPORT":
                return true;
            default:
                throw new ShopException("неизвестная команда " + command, lineNo);
        }
        return false;
    }

    /** Линейный поиск заказа по id — O(n) на каждый вызов. */
    static Order findOrder(String id) {
        for (Order o : orders) {
            if (o.id.equals(id)) {
                return o;
            }
        }
        return null;
    }

    static void audit(String message) {
        auditLog.add(message);
        if (auditLog.size() > 1000) {
            auditLog.removeFirst();
        }
    }

    // ---------------------------------------------------------------- отчёты

    static void printReport() {
        System.out.println("=== Отчёт магазина ===");
        System.out.printf("Команд обработано: %d, заказов: %d, отменено: %d%n",
                processedCommands, orders.size(), countCancelled());
        printRevenue();
        printTopProducts(3);
        printCustomers();
        printCategories();
        printDuplicates();
        printVipOrders();
        printLowStock();
        printCitySummary();
        printAuditTail(5);
        printQuantities();
        checkInvariants();
    }

    static void printQuantities() {
        Set<String> cities = new TreeSet<>();
        Set<String> categories = new TreeSet<>();
        for (Customer c : customers.values()) {
            cities.add(c.city);
        }
        for (Product p : products.values()) {
            categories.add(p.category);
        }
        StringBuilder sb = new StringBuilder("Штук по городам:");
        for (String city : cities) {
            sb.append(' ').append(city).append('=').append(totalQuantityForCity(city));
        }
        System.out.println(sb);
        sb = new StringBuilder("Штук по категориям:");
        for (String cat : categories) {
            sb.append(' ').append(cat).append('=').append(totalQuantityForCategory(cat));
        }
        System.out.println(sb);
    }

    static int countCancelled() {
        int n = 0;
        for (Order o : orders) {
            if (o.cancelled) {
                n++;
            }
        }
        return n;
    }

    static void printRevenue() {
        double revenue = 0;
        double maxOrder = 0;
        String maxOrderId = "-";
        for (Order o : orders) {
            if (o.cancelled) {
                continue;
            }
            double p = o.price();
            revenue += p;
            if (p > maxOrder) {
                maxOrder = p;
                maxOrderId = o.id;
            }
        }
        // заложенный дефект «magic-number»: порог крупной выручки и налог без имени
        double tax = revenue * 0.2;
        System.out.printf("Выручка: %.2f (налог %.2f)%n", revenue, tax);
        if (revenue > 50000) {
            System.out.println("Крупная выручка!");
        }
        System.out.printf("Самый дорогой заказ: %s на %.2f%n", maxOrderId, maxOrder);
        int active = orders.size() - countCancelled();
        double avg = active == 0 ? 0 : revenue / active;
        System.out.printf("Средний чек: %.2f%n", avg);
    }

    static void printTopProducts(int limit) {
        List<Product> sorted = new ArrayList<>(products.values());
        sorted.sort(new Comparator<Product>() {
            @Override
            public int compare(Product a, Product b) {
                if (a.sold != b.sold) {
                    return Integer.compare(b.sold, a.sold);
                }
                return a.id.compareTo(b.id);
            }
        });
        System.out.println("Топ товаров:");
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            Product p = sorted.get(i);
            System.out.printf("  %d. %s — продано %d%n", i + 1, p.name, p.sold);
        }
    }

    static void printCustomers() {
        System.out.println("Клиенты:");
        for (Customer c : customers.values()) {
            // заложенный дефект «string-concat-in-loop»: строка собирается конкатенацией в цикле
            String ids = "";
            for (Order o : orders) {
                if (o.customer == c && !o.cancelled) {
                    ids += o.id + " ";
                }
            }
            System.out.println("  " + c.describe() + ": заказов " + c.ordersCount
                    + String.format(", потрачено %.2f", c.totalSpent) + (ids.isEmpty() ? "" : " [" + ids.trim() + "]"));
        }
    }

    static void printCategories() {
        Map<String, Integer> byCategory = new TreeMap<>();
        Map<String, Double> revenueByCategory = new TreeMap<>();
        for (Order o : orders) {
            if (o.cancelled) {
                continue;
            }
            for (OrderLine l : o.lines) {
                String cat = l.product.category;
                byCategory.put(cat, byCategory.getOrDefault(cat, 0) + l.quantity);
                revenueByCategory.merge(cat, l.price() * (100.0 - o.discountPercent) / 100.0, Double::sum);
            }
        }
        System.out.println("Категории:");
        for (Map.Entry<String, Integer> e : byCategory.entrySet()) {
            System.out.printf("  %-12s %5d шт. %10.2f%n", e.getKey(), e.getValue(), revenueByCategory.get(e.getKey()));
        }
    }

    /**
     * Поиск подозрительных дублей: одинаковый клиент и одинаковый состав.
     * Заложенный дефект «nested-loop-same-collection»: O(n²) по списку заказов.
     */
    static void printDuplicates() {
        List<String> duplicates = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            for (int j = 0; j < orders.size(); j++) {
                Order a = orders.get(i);
                Order b = orders.get(j);
                if (i < j && !a.cancelled && !b.cancelled && a.customer == b.customer && sameContent(a, b)) {
                    duplicates.add(a.id + "=" + b.id);
                }
            }
        }
        System.out.println("Возможные дубли: " + (duplicates.isEmpty() ? "нет" : String.join(", ", duplicates)));
    }

    static boolean sameContent(Order a, Order b) {
        if (a.lines.size() != b.lines.size()) {
            return false;
        }
        for (int k = 0; k < a.lines.size(); k++) {
            OrderLine x = a.lines.get(k);
            OrderLine y = b.lines.get(k);
            if (x.product != y.product || x.quantity != y.quantity) {
                return false;
            }
        }
        return true;
    }

    /** Заложенный дефект «collection-lookup-in-loop»: List.contains внутри цикла по заказам. */
    static void printVipOrders() {
        List<String> vipIds = new ArrayList<>();
        for (Customer c : customers.values()) {
            if (c.vip) {
                vipIds.add(c.id);
            }
        }
        int count = 0;
        double sum = 0;
        for (Order o : orders) {
            if (!o.cancelled && vipIds.contains(o.customer.id)) {
                count++;
                sum += o.price();
            }
        }
        System.out.printf("VIP-заказов: %d на %.2f%n", count, sum);
    }

    static void printLowStock() {
        List<String> low = new ArrayList<>();
        for (Product p : products.values()) {
            if (p.stock < 3) {
                low.add(p.id + "(" + p.stock + ")");
            }
        }
        Collections.sort(low);
        System.out.println("Мало на складе: " + (low.isEmpty() ? "—" : low));
    }

    static void printCitySummary() {
        Map<String, Integer> cityOrders = new TreeMap<>();
        Map<String, Double> citySum = new TreeMap<>();
        for (Order o : orders) {
            if (o.cancelled) {
                continue;
            }
            cityOrders.merge(o.customer.city, 1, Integer::sum);
            citySum.merge(o.customer.city, o.price(), Double::sum);
        }
        System.out.println("По городам:");
        for (String city : cityOrders.keySet()) {
            System.out.printf("  %s: %d заказ(ов), %.2f%n", city, cityOrders.get(city), citySum.get(city));
        }
    }

    /** Заложенный дефект «linked-list-index-access»: get(i) у LinkedList в цикле — O(n²). */
    static void printAuditTail(int n) {
        System.out.println("Последние события:");
        int from = Math.max(0, auditLog.size() - n);
        for (int i = from; i < auditLog.size(); i++) {
            System.out.println("  " + auditLog.get(i));
        }
        System.out.println("Всего событий в журнале: " + auditLog.size());
    }

    // ---------------------------------------------------------------- вспомогательное (дублирование)

    /** Заложенный дефект «duplicate-code»: почти копия {@link #totalQuantityForCategory}. */
    static int totalQuantityForCity(String city) {
        int total = 0;
        for (Order o : orders) {
            if (o.cancelled) {
                continue;
            }
            if (!o.customer.city.equals(city)) {
                continue;
            }
            for (OrderLine l : o.lines) {
                total += l.quantity;
            }
        }
        return total;
    }

    static int totalQuantityForCategory(String category) {
        int total = 0;
        for (Order o : orders) {
            if (o.cancelled) {
                continue;
            }
            for (OrderLine l : o.lines) {
                if (!l.product.category.equals(category)) {
                    continue;
                }
                total += l.quantity;
            }
        }
        return total;
    }

    /** Заложенный дефект «empty-catch»: ошибка разбора проглатывается. */
    static int safeParse(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
        }
        return fallback;
    }

    static void checkInvariants() {
        for (Product p : products.values()) {
            if (p.stock < 0) {
                throw new InvariantViolation("отрицательный остаток у " + p.id);
            }
        }
    }
}
