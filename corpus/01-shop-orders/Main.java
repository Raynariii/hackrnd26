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
 *   RETURN <orderId> <sku> <qty> <причина...>
 *   REVIEW <customerId> <sku> <оценка 1..5> <текст...>
 *   SUPPLIER <id> <срок поставки, дней> <название...>
 *   SUPPLY <supplierId> <sku> <qty> <закупочная цена>
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
    static final List<ReturnRecord> returns = new ArrayList<>();
    static final List<Review> reviews = new ArrayList<>();
    static final Map<String, Supplier> suppliers = new TreeMap<>();
    static final List<Supply> supplies = new ArrayList<>();
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
                if (!processExtraCommand(command, parts)) {
                    throw new ShopException("неизвестная команда " + command, lineNo);
                }
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
        printReturns();
        printRatings();
        printLoyalty();
        printAbcAnalysis();
        printSupplies();
        printStockForecast();
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


    // ---------------------------------------------------------------- возвраты, отзывы, поставки

    static class ReturnRecord {
        final Order order;
        final Product product;
        final int quantity;
        final String reason;
        final double refund;

        ReturnRecord(Order order, Product product, int quantity, String reason, double refund) {
            this.order = order;
            this.product = product;
            this.quantity = quantity;
            this.reason = reason;
            this.refund = refund;
        }
    }

    static class Review implements Comparable<Review> {
        final Customer author;
        final Product product;
        final int stars;
        final String text;
        final boolean verifiedPurchase;

        Review(Customer author, Product product, int stars, String text, boolean verifiedPurchase) {
            this.author = author;
            this.product = product;
            this.stars = stars;
            this.text = text;
            this.verifiedPurchase = verifiedPurchase;
        }

        @Override
        public int compareTo(Review other) {
            if (stars != other.stars) {
                return Integer.compare(other.stars, stars);
            }
            return author.id.compareTo(other.author.id);
        }
    }

    static class Supplier extends Entity {
        final String name;
        final int leadDays;

        Supplier(String id, String name, int leadDays) {
            super(id);
            this.name = name;
            this.leadDays = leadDays;
        }

        @Override
        public String describe() {
            return name + " (" + id + ", поставка за " + leadDays + " дн.)";
        }
    }

    static class Supply {
        final Supplier supplier;
        final Product product;
        final int quantity;
        final double unitCost;

        Supply(Supplier supplier, Product product, int quantity, double unitCost) {
            this.supplier = supplier;
            this.product = product;
            this.quantity = quantity;
            this.unitCost = unitCost;
        }
    }

    /** Уровень программы лояльности по сумме покупок. */
    enum Tier {
        BRONZE, SILVER, GOLD, PLATINUM
    }

    static class ReturnRejectedException extends ShopException {
        ReturnRejectedException(String reason, int lineNumber) {
            super("возврат отклонён: " + reason, lineNumber);
        }
    }

    /** Дополнительные команды: возвраты, отзывы, поставщики, поставки. Возвращает false для неизвестной команды. */
    static boolean processExtraCommand(String command, String[] parts) throws ShopException {
        switch (command) {
            case "RETURN" -> {
                if (parts.length < 5) {
                    throw new ShopException("RETURN: ожидается orderId, sku, количество и причина", lineNo);
                }
                Order order = findOrder(parts[1]);
                if (order == null) {
                    throw new UnknownEntityException("заказ", parts[1], lineNo);
                }
                if (order.cancelled) {
                    throw new ReturnRejectedException("заказ " + order.id + " отменён", lineNo);
                }
                OrderLine line = null;
                for (OrderLine l : order.lines) {
                    if (l.product.id.equals(parts[2])) {
                        line = l;
                    }
                }
                if (line == null) {
                    throw new ReturnRejectedException("в заказе " + order.id + " нет товара " + parts[2], lineNo);
                }
                int qty = Integer.parseInt(parts[3]);
                int alreadyReturned = 0;
                for (ReturnRecord r : returns) {
                    if (r.order == order && r.product == line.product) {
                        alreadyReturned += r.quantity;
                    }
                }
                if (qty <= 0 || qty + alreadyReturned > line.quantity) {
                    throw new ReturnRejectedException("можно вернуть не больше " + (line.quantity - alreadyReturned) + " шт.", lineNo);
                }
                String reason = String.join(" ", Arrays.copyOfRange(parts, 4, parts.length));
                double refund = line.product.price * qty * (100.0 - order.discountPercent) / 100.0;
                if (reason.toLowerCase().contains("передумал")) {
                    refund = refund * 0.9; // заложенный дефект «magic-number»: удержание 10 % без имени
                }
                returns.add(new ReturnRecord(order, line.product, qty, reason, refund));
                line.product.stock += qty;
                line.product.sold -= qty;
                order.customer.totalSpent -= refund;
                audit("возврат " + order.id + " " + line.product.id + " ×" + qty);
                return true;
            }
            case "REVIEW" -> {
                if (parts.length < 5) {
                    throw new ShopException("REVIEW: ожидается customerId, sku, оценка и текст", lineNo);
                }
                Customer author = customers.get(parts[1]);
                if (author == null) {
                    throw new UnknownEntityException("клиент", parts[1], lineNo);
                }
                Product product = products.get(parts[2]);
                if (product == null) {
                    throw new UnknownEntityException("товар", parts[2], lineNo);
                }
                int stars = Integer.parseInt(parts[3]);
                if (stars < 1 || stars > 5) {
                    throw new ShopException("оценка должна быть от 1 до 5, а не " + stars, lineNo);
                }
                boolean verified = false;
                for (Order o : orders) {
                    if (o.customer == author && !o.cancelled) {
                        for (OrderLine l : o.lines) {
                            if (l.product == product) {
                                verified = true;
                            }
                        }
                    }
                }
                String text = String.join(" ", Arrays.copyOfRange(parts, 4, parts.length));
                reviews.add(new Review(author, product, stars, text, verified));
                return true;
            }
            case "SUPPLIER" -> {
                if (parts.length < 4) {
                    throw new ShopException("SUPPLIER: ожидается id, срок и название", lineNo);
                }
                int lead = Integer.parseInt(parts[2]);
                if (lead < 0) {
                    throw new ShopException("срок поставки не может быть отрицательным", lineNo);
                }
                String name = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
                suppliers.put(parts[1], new Supplier(parts[1], name, lead));
                return true;
            }
            case "SUPPLY" -> {
                if (parts.length < 5) {
                    throw new ShopException("SUPPLY: ожидается supplierId, sku, количество и цена", lineNo);
                }
                Supplier supplier = suppliers.get(parts[1]);
                if (supplier == null) {
                    throw new UnknownEntityException("поставщик", parts[1], lineNo);
                }
                Product product = products.get(parts[2]);
                if (product == null) {
                    throw new UnknownEntityException("товар", parts[2], lineNo);
                }
                int qty = Integer.parseInt(parts[3]);
                double cost = Double.parseDouble(parts[4]);
                if (qty <= 0 || cost < 0) {
                    throw new ShopException("SUPPLY: количество и цена должны быть положительными", lineNo);
                }
                supplies.add(new Supply(supplier, product, qty, cost));
                product.stock += qty;
                audit("поставка " + product.id + " +" + qty + " от " + supplier.id);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---------------------------------------------------------------- отчёты по возвратам, отзывам, поставкам

    static void printReturns() {
        if (returns.isEmpty()) {
            System.out.println("Возвраты: нет");
            return;
        }
        double total = 0;
        Map<String, Integer> byReason = new TreeMap<>();
        // заложенный дефект «string-concat-in-loop»
        String list = "";
        for (ReturnRecord r : returns) {
            total += r.refund;
            byReason.merge(r.reason.toLowerCase(), r.quantity, Integer::sum);
            list += r.order.id + "/" + r.product.id + "×" + r.quantity + " ";
        }
        System.out.printf("Возвраты: %d на %.2f [%s]%n", returns.size(), total, list.trim());
        for (Map.Entry<String, Integer> e : byReason.entrySet()) {
            System.out.println("  причина «" + e.getKey() + "»: " + e.getValue() + " шт.");
        }
    }

    /**
     * Рейтинг товаров по отзывам. Заложенный дефект: для каждого товара перебираются все отзывы — O(товары × отзывы)
     * вместо одной группировки.
     */
    static void printRatings() {
        System.out.println("Рейтинг товаров:");
        boolean any = false;
        for (Product p : products.values()) {
            int count = 0;
            int sum = 0;
            int verified = 0;
            for (Review r : reviews) {
                if (r.product == p) {
                    count++;
                    sum += r.stars;
                    if (r.verifiedPurchase) {
                        verified++;
                    }
                }
            }
            if (count == 0) {
                continue;
            }
            any = true;
            double avg = (double) sum / count;
            String stars = "★".repeat((int) Math.round(avg)) + "☆".repeat(5 - (int) Math.round(avg));
            System.out.printf("  %-28s %s %.2f (%d отз., подтверждённых %d)%n", p.name, stars, avg, count, verified);
        }
        if (!any) {
            System.out.println("  отзывов нет");
            return;
        }
        List<Review> sorted = new ArrayList<>(reviews);
        Collections.sort(sorted);
        Review best = sorted.get(0);
        Review worst = sorted.get(sorted.size() - 1);
        System.out.println("  лучший отзыв: " + best.author.name + " о «" + best.product.name + "»: " + best.text);
        System.out.println("  худший отзыв: " + worst.author.name + " о «" + worst.product.name + "»: " + worst.text);
    }

    static Tier tierOf(Customer c) {
        // заложенный дефект «magic-number»: пороги уровней без имён
        if (c.totalSpent >= 100000) {
            return Tier.PLATINUM;
        } else if (c.totalSpent >= 30000) {
            return Tier.GOLD;
        } else if (c.totalSpent >= 10000) {
            return Tier.SILVER;
        }
        return Tier.BRONZE;
    }

    static void printLoyalty() {
        Map<Tier, List<String>> byTier = new EnumMap<>(Tier.class);
        for (Customer c : customers.values()) {
            byTier.computeIfAbsent(tierOf(c), t -> new ArrayList<>()).add(c.name);
        }
        System.out.println("Лояльность:");
        for (Tier t : Tier.values()) {
            List<String> names = byTier.getOrDefault(t, Collections.emptyList());
            String bonus;
            switch (t) {
                case PLATINUM:
                    bonus = "кешбэк 7%";
                    break;
                case GOLD:
                    bonus = "кешбэк 5%";
                    break;
                case SILVER:
                    bonus = "кешбэк 3%";
                    break;
                default:
                    bonus = "без кешбэка";
            }
            System.out.printf("  %-8s %-12s %s%n", t, bonus, names.isEmpty() ? "—" : String.join(", ", names));
        }
    }

    /** ABC-анализ: A — товары, дающие 80 % выручки, B — следующие 15 %, C — остальные. */
    static void printAbcAnalysis() {
        Map<Product, Double> revenue = new HashMap<>();
        for (Order o : orders) {
            if (o.cancelled) {
                continue;
            }
            for (OrderLine l : o.lines) {
                revenue.merge(l.product, l.price() * (100.0 - o.discountPercent) / 100.0, Double::sum);
            }
        }
        for (ReturnRecord r : returns) {
            revenue.merge(r.product, -r.refund, Double::sum);
        }
        List<Map.Entry<Product, Double>> entries = new ArrayList<>(revenue.entrySet());
        entries.sort((a, b) -> {
            int byValue = Double.compare(b.getValue(), a.getValue());
            return byValue != 0 ? byValue : a.getKey().id.compareTo(b.getKey().id);
        });
        double total = 0;
        for (Map.Entry<Product, Double> e : entries) {
            total += Math.max(0, e.getValue());
        }
        System.out.println("ABC-анализ:");
        if (total <= 0) {
            System.out.println("  нет продаж");
            return;
        }
        double cumulative = 0;
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        StringBuilder c = new StringBuilder();
        for (Map.Entry<Product, Double> e : entries) {
            double share = cumulative / total;
            StringBuilder target = share < 0.8 ? a : share < 0.95 ? b : c;
            target.append(e.getKey().id).append(' ');
            cumulative += Math.max(0, e.getValue());
        }
        System.out.println("  A: " + (a.length() == 0 ? "—" : a.toString().trim()));
        System.out.println("  B: " + (b.length() == 0 ? "—" : b.toString().trim()));
        System.out.println("  C: " + (c.length() == 0 ? "—" : c.toString().trim()));
    }

    static void printSupplies() {
        if (supplies.isEmpty()) {
            System.out.println("Поставки: нет");
            return;
        }
        System.out.println("Поставки и маржа:");
        for (Supplier s : suppliers.values()) {
            int units = 0;
            double cost = 0;
            double potential = 0;
            for (Supply sp : supplies) {
                if (sp.supplier == s) {
                    units += sp.quantity;
                    cost += sp.quantity * sp.unitCost;
                    potential += sp.quantity * sp.product.price;
                }
            }
            if (units == 0) {
                continue;
            }
            double margin = potential == 0 ? 0 : (potential - cost) / potential * 100;
            System.out.printf("  %-30s %6d шт. закупка %12.2f, маржа %5.1f%%%n", s.describe(), units, cost, margin);
        }
    }

    /**
     * Прогноз запасов: сколько ещё заказов выдержит остаток при среднем размере заказа товара. Поставщик с самым
     * коротким сроком предлагается для пополнения.
     */
    static void printStockForecast() {
        System.out.println("Прогноз запасов:");
        int shown = 0;
        for (Product p : products.values()) {
            int ordersWithProduct = 0;
            int units = 0;
            for (Order o : orders) {
                if (o.cancelled) {
                    continue;
                }
                for (OrderLine l : o.lines) {
                    if (l.product == p) {
                        ordersWithProduct++;
                        units += l.quantity;
                    }
                }
            }
            if (ordersWithProduct == 0) {
                continue;
            }
            double perOrder = (double) units / ordersWithProduct;
            int ordersLeft = (int) Math.floor(p.stock / perOrder);
            if (ordersLeft > 5) {
                continue;
            }
            Supplier fastest = null;
            for (Supply sp : supplies) {
                if (sp.product == p && (fastest == null || sp.supplier.leadDays < fastest.leadDays)) {
                    fastest = sp.supplier;
                }
            }
            System.out.printf("  %-6s хватит примерно на %d заказ(ов)%s%n", p.id, ordersLeft,
                    fastest == null ? ", поставщика нет" : ", заказать у " + fastest.name + " (" + fastest.leadDays + " дн.)");
            shown++;
        }
        if (shown == 0) {
            System.out.println("  запасов достаточно");
        }
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
