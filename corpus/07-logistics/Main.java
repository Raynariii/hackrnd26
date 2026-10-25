import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.*;

/**
 * Монолит «Служба доставки»: карта дорог, курьеры, заказы, тарифы, промокоды, маршруты и отчёты.
 * Читает команды из stdin (по одной в строке), в конце печатает отчёт.
 *
 * Команды:
 *   NODE <id> <название...>                 — пункт на карте
 *   ROAD <a> <b> <км> [TOLL]                — дорога в обе стороны (TOLL — платная)
 *   COURIER <id> <BIKE|CAR|TRUCK> <база> <грузоподъёмность-кг>
 *   PROMO <код> <PERCENT|FIXED|FREE_KM> <значение>
 *   ORDER <id> <откуда> <куда> <вес-кг> <ECONOMY|STANDARD|EXPRESS> [промокод]
 *   ASSIGN                                  — распределить ожидающие заказы
 *   DELIVER <id> | FAIL <id> <причина...>
 *   ROUTE <a> <b>                           — кратчайший путь
 *   MATRIX                                  — матрица расстояний (Флойд — Уоршелл)
 *   STATS | TOP <n> | AUDIT <n> | CSV | HELP | REPORT
 *
 * Строки, начинающиеся с #, и пустые строки пропускаются.
 */
public class Main {

    // ------------------------------------------------------------------ исключения

    /** Проверяемое: ошибка входных данных. */
    static class LogisticsException extends Exception {
        final int line;

        LogisticsException(String message, int line) {
            super(message);
            this.line = line;
        }

        LogisticsException(String message, int line, Throwable cause) {
            super(message, cause);
            this.line = line;
        }
    }

    /** Непроверяемое: заказ не помещается ни к одному курьеру. */
    static class CapacityExceededException extends RuntimeException {
        CapacityExceededException(String orderId, double weight) {
            super("заказ " + orderId + " весит " + weight + " кг — нет подходящего курьера");
        }
    }

    static final class UnknownNodeException extends LogisticsException {
        UnknownNodeException(String node, int line) {
            super("неизвестный пункт: " + node, line);
        }
    }

    // ------------------------------------------------------------------ модель

    record Point(String id, String name) {
        Point {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("пустой id пункта");
            }
            name = name == null || name.isBlank() ? id : name.strip();
        }
    }

    record Road(String from, String to, int km, boolean toll) {
        Road {
            if (km <= 0) {
                throw new IllegalArgumentException("длина дороги должна быть положительной: " + km);
            }
        }

        Road reversed() {
            return new Road(to, from, km, toll);
        }
    }

    enum Vehicle {
        BIKE(15, 18, 0.0),
        CAR(120, 45, 9.5),
        TRUCK(1500, 35, 24.0);

        final int capacityLimitKg;
        final int speedKmh;
        final double fuelPer100;

        Vehicle(int capacityLimitKg, int speedKmh, double fuelPer100) {
            this.capacityLimitKg = capacityLimitKg;
            this.speedKmh = speedKmh;
            this.fuelPer100 = fuelPer100;
        }

        boolean canUseTollRoads() {
            return switch (this) {
                case BIKE -> false;
                case CAR, TRUCK -> true;
            };
        }

        int minutesFor(int km) {
            return (int) Math.ceil(km * 60.0 / speedKmh);
        }
    }

    enum Tariff {
        ECONOMY("Эконом") {
            @Override
            long price(int km, double weightKg) {
                return 9900 + km * 1200L + Math.round(weightKg * 300);
            }
        },
        STANDARD("Стандарт") {
            @Override
            long price(int km, double weightKg) {
                return 14900 + km * 1800L + Math.round(weightKg * 450);
            }
        },
        EXPRESS("Экспресс") {
            @Override
            long price(int km, double weightKg) {
                long base = 24900 + km * 3000L + Math.round(weightKg * 700);
                return km > 30 ? base + base / 4 : base;
            }

            @Override
            int priority() {
                return 0;
            }
        };

        final String title;

        Tariff(String title) {
            this.title = title;
        }

        /** Цена в копейках. */
        abstract long price(int km, double weightKg);

        int priority() {
            return ordinal() == 0 ? 2 : 1;
        }
    }

    enum Status { PENDING, ASSIGNED, DELIVERED, FAILED }

    sealed interface Discount permits Percent, Fixed, FreeKm {
    }

    record Percent(int percent) implements Discount {
        Percent {
            if (percent < 1 || percent > 90) {
                throw new IllegalArgumentException("процент скидки вне 1..90: " + percent);
            }
        }
    }

    record Fixed(long kopecks) implements Discount {
    }

    record FreeKm(int km) implements Discount {
    }

    static final class Order implements Comparable<Order> {
        final String id;
        final String from;
        final String to;
        final double weightKg;
        final Tariff tariff;
        final String promo;
        final int sequence;
        Status status = Status.PENDING;
        String courierId;
        int routeKm = -1;
        long priceKopecks;
        String failure;

        Order(String id, String from, String to, double weightKg, Tariff tariff, String promo, int sequence) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.weightKg = weightKg;
            this.tariff = tariff;
            this.promo = promo;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(Order other) {
            int byTariff = Integer.compare(tariff.priority(), other.tariff.priority());
            if (byTariff != 0) {
                return byTariff;
            }
            int byWeight = Double.compare(other.weightKg, weightKg);
            return byWeight != 0 ? byWeight : Integer.compare(sequence, other.sequence);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Order other && other.id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return id + "[" + from + "→" + to + ", " + weightKg + " кг, " + tariff.title + ", " + status + "]";
        }
    }

    class Courier {
        final String id;
        final Vehicle vehicle;
        final String home;
        final double capacityKg;
        String position;
        double loadKg;
        int totalKm;
        int deliveries;
        final List<String> orderIds = new ArrayList<>();

        {
            couriersCreated++;
        }

        Courier(String id, Vehicle vehicle, String home, double capacityKg) {
            this.id = id;
            this.vehicle = vehicle;
            this.home = home;
            this.position = home;
            this.capacityKg = Math.min(capacityKg, vehicle.capacityLimitKg);
        }

        boolean fits(double weightKg) {
            return loadKg + weightKg <= capacityKg + 1e-9;
        }

        double utilisation() {
            return capacityKg == 0 ? 0 : loadKg / capacityKg;
        }
    }

    /** Журнал событий с собственным итератором — от новых к старым. */
    static class EventLog implements Iterable<String> {
        private final String[] ring;
        private int size;
        private int head;

        EventLog(int capacity) {
            ring = new String[capacity];
        }

        void add(String event) {
            ring[head] = event;
            head = (head + 1) % ring.length;
            size = Math.min(size + 1, ring.length);
        }

        int size() {
            return size;
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<>() {
                private int returned;

                @Override
                public boolean hasNext() {
                    return returned < size;
                }

                @Override
                public String next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    returned++;
                    return ring[Math.floorMod(head - returned, ring.length)];
                }
            };
        }
    }

    /** Отчёт печатается целиком при закрытии — проверка try-with-resources. */
    static final class ReportSection implements AutoCloseable {
        private final StringBuilder body = new StringBuilder();
        private final String title;

        ReportSection(String title) {
            this.title = title;
        }

        ReportSection line(String format, Object... args) {
            body.append("  ").append(String.format(Locale.ROOT, format, args)).append('\n');
            return this;
        }

        @Override
        public void close() {
            System.out.print("== " + title + " ==\n" + body);
        }
    }

    @FunctionalInterface
    interface PriceRule {
        long apply(Order order, long price);

        default PriceRule then(PriceRule next) {
            return (order, price) -> next.apply(order, apply(order, price));
        }

        static PriceRule identity() {
            return (order, price) -> price;
        }
    }

    /** Сумматор по ключам — дженерик с ограничением. */
    static final class Ledger<K extends Comparable<K>, V extends Number> {
        private final TreeMap<K, Double> sums = new TreeMap<>();

        void add(K key, V value) {
            sums.merge(key, value.doubleValue(), Double::sum);
        }

        Optional<Map.Entry<K, Double>> top() {
            return sums.entrySet().stream().max(Map.Entry.comparingByValue());
        }

        Map<K, Double> view() {
            return Collections.unmodifiableMap(sums);
        }
    }

    // ------------------------------------------------------------------ состояние

    static final int MAX_NODES = 64;
    static final Map<String, String> ALIASES;
    static final String HELP = """
            Команды: NODE, ROAD, COURIER, PROMO, ORDER, ASSIGN, DELIVER, FAIL,
            ROUTE, MATRIX, STATS, TOP, AUDIT, CSV, HELP, REPORT.
            Пример: ORDER o1 A B 2.5 EXPRESS SALE10""";

    static {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("ADD", "ORDER");
        aliases.put("PATH", "ROUTE");
        aliases.put("?", "HELP");
        ALIASES = Collections.unmodifiableMap(aliases);
    }

    static int couriersCreated;

    final Map<String, Point> points = new LinkedHashMap<>();
    final Map<String, List<Road>> roads = new TreeMap<>();
    final Map<String, Courier> couriers = new LinkedHashMap<>();
    final Map<String, Order> orders = new LinkedHashMap<>();
    final Map<String, Discount> promos = new HashMap<>();
    final List<String> pending = new ArrayList<>();
    final LinkedList<String> audit = new LinkedList<>();
    final EventLog events = new EventLog(16);
    final Ledger<String, Long> revenueByTariff = new Ledger<>();
    final List<String> errors = new ArrayList<>();
    int lineNo;
    int commands;
    long flags;

    // ------------------------------------------------------------------ точка входа

    public static void main(String[] args) {
        Main app = new Main();
        Scanner in = new Scanner(System.in, StandardCharsets.UTF_8);
        while (in.hasNextLine()) {
            String raw = in.nextLine();
            app.lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                app.process(line);
            } catch (LogisticsException e) {
                app.error("строка " + e.line + ": " + e.getMessage()
                        + (e.getCause() != null ? " (" + e.getCause().getClass().getSimpleName() + ")" : ""));
            } catch (CapacityExceededException | IllegalArgumentException e) {
                app.error("строка " + app.lineNo + ": " + e.getMessage());
            }
        }
        app.finish();
        System.exit(app.errors.isEmpty() ? 0 : 3);
    }

    void error(String message) {
        errors.add(message);
        System.err.println("ошибка: " + message);
    }

    void log(String event) {
        audit.add(event);
        events.add(event);
    }

    // ------------------------------------------------------------------ команды

    /** Длинный метод с большим switch — заложенный дефект «длинный метод». */
    void process(String line) throws LogisticsException {
        String[] p = line.split("\\s+");
        String cmd = ALIASES.getOrDefault(p[0].toUpperCase(Locale.ROOT), p[0].toUpperCase(Locale.ROOT));
        commands++;
        switch (cmd) {
            case "NODE": {
                need(p, 2);
                if (points.size() >= MAX_NODES) {
                    throw new LogisticsException("слишком много пунктов", lineNo);
                }
                String name = p.length > 2 ? String.join(" ", Arrays.copyOfRange(p, 2, p.length)) : p[1];
                points.put(p[1], new Point(p[1], name));
                roads.computeIfAbsent(p[1], k -> new ArrayList<>());
                log("пункт " + p[1]);
                break;
            }
            case "ROAD": {
                need(p, 4);
                requireNode(p[1]);
                requireNode(p[2]);
                int km = parseInt(p[3]);
                boolean toll = p.length > 4 && p[4].equalsIgnoreCase("TOLL");
                Road road = new Road(p[1], p[2], km, toll);
                roads.get(p[1]).add(road);
                roads.get(p[2]).add(road.reversed());
                if (toll) {
                    flags |= 1L << 1;
                }
                log("дорога " + p[1] + "-" + p[2] + " " + km + " км" + (toll ? " (платная)" : ""));
                break;
            }
            case "COURIER": {
                need(p, 5);
                Vehicle vehicle;
                try {
                    vehicle = Vehicle.valueOf(p[2].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new LogisticsException("неизвестный транспорт: " + p[2], lineNo, e);
                }
                requireNode(p[3]);
                double capacity = parseDouble(p[4]);
                Courier c = new Courier(p[1], vehicle, p[3], capacity);
                couriers.put(c.id, c);
                log("курьер " + c.id + " на " + vehicle.name().toLowerCase(Locale.ROOT));
                break;
            }
            case "PROMO": {
                need(p, 4);
                String code = p[1].toUpperCase(Locale.ROOT);
                int value = parseInt(p[3]);
                Discount d = switch (p[2].toUpperCase(Locale.ROOT)) {
                    case "PERCENT" -> new Percent(value);
                    case "FIXED" -> new Fixed(value * 100L);
                    case "FREE_KM" -> {
                        if (value > 50) {
                            throw new LogisticsException("бесплатных км не больше 50", lineNo);
                        }
                        yield new FreeKm(value);
                    }
                    default -> throw new LogisticsException("тип промокода: " + p[2], lineNo);
                };
                promos.put(code, d);
                log("промокод " + code);
                break;
            }
            case "ORDER": {
                need(p, 6);
                if (orders.containsKey(p[1])) {
                    throw new LogisticsException("заказ уже есть: " + p[1], lineNo);
                }
                requireNode(p[2]);
                requireNode(p[3]);
                double weight = parseDouble(p[4]);
                if (weight <= 0 || weight > 2000) {
                    throw new LogisticsException("вес вне диапазона: " + p[4], lineNo);
                }
                Tariff tariff;
                try {
                    tariff = Tariff.valueOf(p[5].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new LogisticsException("неизвестный тариф: " + p[5], lineNo, e);
                }
                String promo = p.length > 6 ? p[6].toUpperCase(Locale.ROOT) : null;
                Order o = new Order(p[1], p[2], p[3], weight, tariff, promo, orders.size());
                orders.put(o.id, o);
                pending.add(o.id);
                log("заказ " + o.id);
                break;
            }
            case "ASSIGN":
                assign();
                break;
            case "DELIVER":
            case "FAIL": {
                need(p, 2);
                Order o = orders.get(p[1]);
                if (o == null) {
                    throw new LogisticsException("нет заказа " + p[1], lineNo);
                }
                if (o.status != Status.ASSIGNED) {
                    throw new LogisticsException("заказ " + o.id + " не в пути (" + o.status + ")", lineNo);
                }
                Courier c = couriers.get(o.courierId);
                c.loadKg -= o.weightKg;
                if (cmd.equals("DELIVER")) {
                    o.status = Status.DELIVERED;
                    c.deliveries++;
                    c.position = o.to;
                    revenueByTariff.add(o.tariff.title, o.priceKopecks);
                    log("доставлен " + o.id);
                } else {
                    o.status = Status.FAILED;
                    o.failure = p.length > 2 ? String.join(" ", Arrays.copyOfRange(p, 2, p.length)) : "без причины";
                    log("сорван " + o.id + ": " + o.failure);
                }
                break;
            }
            case "ROUTE": {
                need(p, 3);
                requireNode(p[1]);
                requireNode(p[2]);
                Path path = shortestPath(p[1], p[2], true);
                if (path == null) {
                    System.out.println("маршрут " + p[1] + " → " + p[2] + ": нет пути");
                } else {
                    System.out.println("маршрут " + p[1] + " → " + p[2] + ": " + String.join(" → ", path.nodes())
                            + " (" + path.km() + " км)");
                }
                break;
            }
            case "MATRIX":
                printMatrix();
                break;
            case "STATS":
                printStats();
                break;
            case "TOP":
                printTop(p.length > 1 ? parseInt(p[1]) : 3);
                break;
            case "AUDIT":
                printAudit(p.length > 1 ? parseInt(p[1]) : 5);
                break;
            case "CSV":
                System.out.print(exportCsv());
                break;
            case "HELP":
                System.out.println(HELP);
                break;
            case "REPORT":
                flags |= 1;
                report();
                break;
            default:
                throw new LogisticsException("неизвестная команда: " + p[0], lineNo);
        }
    }

    static void need(String[] p, int n) throws LogisticsException {
        if (p.length < n) {
            throw new LogisticsException("мало аргументов у " + p[0] + ": нужно " + (n - 1), -1);
        }
    }

    void requireNode(String id) throws UnknownNodeException {
        if (!points.containsKey(id)) {
            throw new UnknownNodeException(id, lineNo);
        }
    }

    int parseInt(String s) throws LogisticsException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new LogisticsException("не число: " + s, lineNo, e);
        }
    }

    double parseDouble(String s) throws LogisticsException {
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException(s);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new LogisticsException("не число: " + s, lineNo, e);
        }
    }

    // ------------------------------------------------------------------ маршруты

    record Path(List<String> nodes, int km) {
    }

    /** Дейкстра на PriorityQueue; велосипеды не ездят по платным дорогам. */
    Path shortestPath(String from, String to, boolean allowToll) {
        Map<String, Integer> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<Map.Entry<String, Integer>> queue =
                new PriorityQueue<>(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        dist.put(from, 0);
        queue.add(Map.entry(from, 0));
        while (!queue.isEmpty()) {
            Map.Entry<String, Integer> top = queue.poll();
            String node = top.getKey();
            if (top.getValue() > dist.getOrDefault(node, Integer.MAX_VALUE)) {
                continue;
            }
            if (node.equals(to)) {
                break;
            }
            for (Road r : roads.getOrDefault(node, List.of())) {
                if (r.toll() && !allowToll) {
                    continue;
                }
                int candidate;
                try {
                    candidate = Math.addExact(top.getValue(), r.km());
                } catch (ArithmeticException overflow) {
                    continue;
                }
                if (candidate < dist.getOrDefault(r.to(), Integer.MAX_VALUE)) {
                    dist.put(r.to(), candidate);
                    prev.put(r.to(), node);
                    queue.add(Map.entry(r.to(), candidate));
                }
            }
        }
        if (!dist.containsKey(to)) {
            return null;
        }
        Deque<String> nodes = new ArrayDeque<>();
        for (String at = to; at != null; at = prev.get(at)) {
            nodes.addFirst(at);
        }
        return new Path(List.copyOf(nodes), dist.get(to));
    }

    /** Флойд — Уоршелл: тройной цикл O(n³) по пунктам. */
    int[][] distanceMatrix(List<String> ids) {
        int n = ids.size();
        final int inf = Integer.MAX_VALUE / 4;
        int[][] d = new int[n][n];
        for (int[] row : d) {
            Arrays.fill(row, inf);
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(ids.get(i), i);
            d[i][i] = 0;
        }
        roads.forEach((from, list) -> {
            for (Road r : list) {
                int a = index.get(from);
                int b = index.get(r.to());
                d[a][b] = Math.min(d[a][b], r.km());
            }
        });
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (d[i][k] + d[k][j] < d[i][j]) {
                        d[i][j] = d[i][k] + d[k][j];
                    }
                }
            }
        }
        return d;
    }

    void printMatrix() {
        List<String> ids = new ArrayList<>(points.keySet());
        int[][] d = distanceMatrix(ids);
        StringBuilder sb = new StringBuilder("матрица расстояний:\n      ");
        for (String id : ids) {
            sb.append(String.format(Locale.ROOT, "%6s", id));
        }
        sb.append('\n');
        for (int i = 0; i < ids.size(); i++) {
            sb.append(String.format(Locale.ROOT, "%6s", ids.get(i)));
            for (int j = 0; j < ids.size(); j++) {
                sb.append(d[i][j] >= Integer.MAX_VALUE / 4 ? "     -" : String.format(Locale.ROOT, "%6d", d[i][j]));
            }
            sb.append('\n');
        }
        System.out.print(sb);
    }

    /** Достижимость обходом в ширину. */
    Set<String> reachable(String from) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (Road r : roads.getOrDefault(node, List.of())) {
                if (seen.add(r.to())) {
                    queue.add(r.to());
                }
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ распределение

    /**
     * Жадное распределение: заказы по приоритету тарифа, каждому — ближайший подходящий курьер.
     * Заложенные дефекты: глубокая вложенность и {@code pending.contains} в цикле по списку.
     */
    void assign() {
        List<Order> queue = new ArrayList<>();
        for (Order o : orders.values()) {
            if (pending.contains(o.id) && o.status == Status.PENDING) {
                queue.add(o);
            }
        }
        Collections.sort(queue);
        int assigned = 0;
        outer:
        for (Order o : queue) {
            Courier best = null;
            int bestKm = Integer.MAX_VALUE;
            for (Courier c : couriers.values()) {
                if (c.fits(o.weightKg)) {
                    Path toPickup = shortestPath(c.position, o.from, c.vehicle.canUseTollRoads());
                    if (toPickup != null) {
                        Path delivery = shortestPath(o.from, o.to, c.vehicle.canUseTollRoads());
                        if (delivery != null) {
                            int km = toPickup.km() + delivery.km();
                            if (km < bestKm || km == bestKm && best != null && c.id.compareTo(best.id) < 0) {
                                best = c;
                                bestKm = km;
                            }
                        } else {
                            continue outer;
                        }
                    }
                }
            }
            if (best == null) {
                if (couriers.values().stream().noneMatch(c -> c.capacityKg >= o.weightKg)) {
                    error("строка " + lineNo + ": " + new CapacityExceededException(o.id, o.weightKg).getMessage());
                    o.status = Status.FAILED;
                    o.failure = "перевес";
                    pending.remove(o.id);
                }
                continue;
            }
            Path delivery = shortestPath(o.from, o.to, best.vehicle.canUseTollRoads());
            o.routeKm = delivery.km();
            o.priceKopecks = finalPrice(o);
            o.courierId = best.id;
            o.status = Status.ASSIGNED;
            best.loadKg += o.weightKg;
            best.totalKm += bestKm;
            best.orderIds.add(o.id);
            pending.remove(o.id);
            assigned++;
            log("назначен " + o.id + " → " + best.id + " (" + bestKm + " км)");
        }
        System.out.println("назначено: " + assigned + ", ждут: " + pending.size());
    }

    long finalPrice(Order o) {
        PriceRule base = (order, price) -> order.tariff.price(order.routeKm, order.weightKg);
        PriceRule promo = (order, price) -> applyDiscount(promos.get(order.promo), order, price);
        PriceRule rounding = (order, price) -> Math.max(0, (price + 50) / 100 * 100);
        return PriceRule.identity().then(base).then(promo).then(rounding).apply(o, 0);
    }

    static long applyDiscount(Discount d, Order o, long price) {
        if (d == null) {
            return price;
        }
        return switch (d) {
            case Percent p when o.tariff == Tariff.EXPRESS -> price - price * Math.min(p.percent(), 20) / 100;
            case Percent p -> price - price * p.percent() / 100;
            case Fixed f -> Math.max(price / 2, price - f.kopecks());
            case FreeKm f -> price - (long) Math.min(f.km(), o.routeKm) * 1200;
        };
    }

    // ------------------------------------------------------------------ отчёты

    /** Дублирование с totalKmFor: почти одинаковые методы — заложенный дефект. */
    double totalWeightFor(String courierId) {
        double sum = 0;
        for (Order o : orders.values()) {
            if (courierId.equals(o.courierId) && o.status == Status.DELIVERED) {
                sum += o.weightKg;
            }
        }
        return sum;
    }

    int totalKmFor(String courierId) {
        int sum = 0;
        for (Order o : orders.values()) {
            if (courierId.equals(o.courierId) && o.status == Status.DELIVERED) {
                sum += o.routeKm;
            }
        }
        return sum;
    }

    void printStats() {
        Map<Status, Long> byStatus = orders.values().stream()
                .collect(Collectors.groupingBy(o -> o.status, () -> new EnumMap<>(Status.class), Collectors.counting()));
        Map<Boolean, List<Order>> heavy = orders.values().stream()
                .collect(Collectors.partitioningBy(o -> o.weightKg >= 10));
        System.out.println("статусы: " + byStatus);
        System.out.println("тяжёлых: " + heavy.get(true).size() + ", лёгких: " + heavy.get(false).size());
        IntSummaryStatistics km = orders.values().stream().filter(o -> o.routeKm >= 0)
                .mapToInt(o -> o.routeKm).summaryStatistics();
        System.out.printf(Locale.ROOT, "маршруты: %d шт., %d..%d км, среднее %.1f%n",
                km.getCount(), km.getCount() == 0 ? 0 : km.getMin(), km.getCount() == 0 ? 0 : km.getMax(), km.getAverage());
    }

    void printTop(int n) {
        if (n <= 0) {
            System.out.println("топ: пусто");
            return;
        }
        String top = couriers.values().stream()
                .sorted(Comparator.comparingInt((Courier c) -> c.deliveries).reversed().thenComparing(c -> c.id))
                .limit(n)
                .map(c -> c.id + "=" + c.deliveries)
                .collect(Collectors.joining(", ", "топ курьеров: ", ""));
        System.out.println(top);
    }

    /** Последние n событий журнала. Заложенный дефект: LinkedList.get(i) в цикле, и цикл идёт не с нуля. */
    void printAudit(int n) {
        int start = Math.max(0, audit.size() - n);
        for (int i = start; i < audit.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + audit.get(i));
        }
    }

    /** Заложенный дефект: LinkedList.get(i) в цикле с нуля. */
    int auditLength() {
        int chars = 0;
        for (int i = 0; i < audit.size(); i++) {
            chars += audit.get(i).length();
        }
        return chars;
    }

    /** Заложенные дефекты: конкатенация строк в цикле и String.format в цикле. */
    String exportCsv() {
        String csv = "id;from;to;weight;tariff;status;km;price\n";
        for (Order o : orders.values()) {
            csv += o.id + ";" + o.from + ";" + o.to + ";" + String.format(Locale.ROOT, "%.2f", o.weightKg) + ";"
                    + o.tariff.name() + ";" + o.status + ";" + o.routeKm + ";" + o.priceKopecks + "\n";
        }
        return csv;
    }

    /** Заложенный дефект: вложенные циклы по одной коллекции — поиск подозрительных дублей заказов O(n²). */
    List<String> suspiciousDuplicates() {
        List<Order> list = new ArrayList<>(orders.values());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                Order a = list.get(i);
                Order b = list.get(j);
                if (a.from.equals(b.from) && a.to.equals(b.to) && Math.abs(a.weightKg - b.weightKg) < 0.01) {
                    result.add(a.id + "~" + b.id);
                }
            }
        }
        return result;
    }

    /** Заложенный дефект: Pattern.compile в цикле. */
    int countPromoLikeIds() {
        int count = 0;
        for (Order o : orders.values()) {
            Pattern p = Pattern.compile("^[a-z]+\\d{2,}$");
            Matcher m = p.matcher(o.id);
            if (m.matches()) {
                count++;
            }
        }
        return count;
    }

    /** Заложенный дефект: рекурсия без мемоизации — экспоненциальная. Число способов разбить вес на коробки 1/2/5 кг. */
    static long boxings(int kg) {
        if (kg < 0) {
            return 0;
        }
        if (kg == 0) {
            return 1;
        }
        return boxings(kg - 1) + boxings(kg - 2) + boxings(kg - 5);
    }

    /** То же с мемоизацией — для сравнения. */
    static long boxingsMemo(int kg, long[] memo) {
        if (kg < 0) {
            return 0;
        }
        if (kg == 0) {
            return 1;
        }
        if (memo[kg] != 0) {
            return memo[kg];
        }
        return memo[kg] = boxingsMemo(kg - 1, memo) + boxingsMemo(kg - 2, memo) + boxingsMemo(kg - 5, memo);
    }

    static <T extends Comparable<? super T>> T maxOf(Collection<? extends T> items, T fallback) {
        T best = fallback;
        for (T item : items) {
            if (best == null || item.compareTo(best) > 0) {
                best = item;
            }
        }
        return best;
    }

    @SafeVarargs
    static <T> List<T> concat(List<? extends T>... lists) {
        List<T> all = new ArrayList<>();
        for (List<? extends T> l : lists) {
            all.addAll(l);
        }
        return all;
    }

    static String checksum(String s) {
        int h = 0x811C9DC5;
        for (char ch : s.toCharArray()) {
            h ^= ch;
            h *= 0x01000193;
        }
        return Integer.toHexString(h >>> 8 ^ h & 0xFF).toUpperCase(Locale.ROOT);
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        boolean take = true;
        for (char ch : name.toCharArray()) {
            if (Character.isWhitespace(ch) || ch == '-') {
                take = true;
            } else if (take && Character.isLetter(ch)) {
                sb.append(Character.toUpperCase(ch));
                take = false;
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    static String grade(double utilisation) {
        int bucket = (int) (utilisation * 10);
        String result;
        switch (bucket) {
            case 10:
            case 9:
                result = "перегружен";
                break;
            case 8:
            case 7:
            case 6:
                result = "загружен";
                break;
            case 0:
                result = "свободен";
                break;
            default:
                result = "норма";
        }
        return result;
    }

    static String describe(Object value) {
        if (value instanceof Order o && o.status == Status.FAILED) {
            return "сорванный заказ " + o.id;
        } else if (value instanceof Order o) {
            return "заказ " + o.id;
        } else if (value instanceof Courier c) {
            return "курьер " + c.id + " (" + c.vehicle + ")";
        } else if (value instanceof Integer n && n < 0) {
            return "отрицательное " + n;
        }
        return String.valueOf(value);
    }

    String fuelReport() {
        double litres = 0;
        double cost = 0;
        int i = 0;
        do {
            if (couriers.isEmpty()) {
                break;
            }
            Courier c = new ArrayList<>(couriers.values()).get(i);
            double l = c.totalKm * c.vehicle.fuelPer100 / 100.0;
            litres += l;
            cost += l * (c.vehicle == Vehicle.TRUCK ? 61.4 : 56.2);
            i++;
        } while (i < couriers.size());
        return String.format(Locale.ROOT, "топливо: %.1f л, %.2f руб.", litres, cost);
    }

    void finish() {
        if ((flags & 1) == 0) {
            report();
        }
    }

    void report() {
        try (ReportSection orders = new ReportSection("заказы")) {
            Map<Tariff, IntSummaryStatistics> byTariff = this.orders.values().stream()
                    .filter(o -> o.routeKm >= 0)
                    .collect(Collectors.groupingBy(o -> o.tariff, () -> new EnumMap<>(Tariff.class),
                            Collectors.summarizingInt(o -> o.routeKm)));
            orders.line("всего %d, ждут %d, ошибок %d, команд %d", this.orders.size(), pending.size(), errors.size(), commands);
            byTariff.forEach((t, s) -> orders.line("%-9s %d шт., %d км", t.title, s.getCount(), s.getSum()));
            List<String> dups = suspiciousDuplicates();
            if (!dups.isEmpty()) {
                orders.line("возможные дубли: %s", String.join(", ", dups));
            }
            orders.line("id по шаблону: %d", countPromoLikeIds());
        }
        try (ReportSection money = new ReportSection("выручка")) {
            long total = 0;
            for (Map.Entry<String, Double> e : revenueByTariff.view().entrySet()) {
                money.line("%-9s %10.2f руб.", e.getKey(), e.getValue() / 100);
                total += e.getValue().longValue();
            }
            money.line("итого     %10.2f руб.", total / 100.0);
            revenueByTariff.top().ifPresentOrElse(
                    e -> money.line("лидер: %s", e.getKey()),
                    () -> money.line("лидер: нет доставок"));
        }
        try (ReportSection fleet = new ReportSection("курьеры")) {
            for (Courier c : couriers.values()) {
                fleet.line("%-4s %-5s %-8s %6.1f/%6.1f кг %-10s доставок %d, %d км, вес %.1f кг, км по заказам %d",
                        c.id, c.vehicle, c.position, c.loadKg, c.capacityKg, grade(c.utilisation()), c.deliveries,
                        c.totalKm, totalWeightFor(c.id), totalKmFor(c.id));
            }
            fleet.line("%s", fuelReport());
            fleet.line("создано курьеров: %d", couriersCreated);
        }
        try (ReportSection misc = new ReportSection("прочее")) {
            Optional<Order> heaviest = orders.values().stream().max(Comparator.comparingDouble(o -> o.weightKg));
            misc.line("самый тяжёлый: %s", heaviest.map(Main::describe).orElse("нет"));
            Order maxOrder = maxOf(orders.values(), null);
            misc.line("первый в очереди приоритета: %s", maxOrder == null ? "нет" : maxOrder.id);
            misc.line("упаковок 12 кг: %d (мемо %d)", boxings(12), boxingsMemo(12, new long[13]));
            String allIds = concat(new ArrayList<>(orders.keySet()), new ArrayList<>(couriers.keySet())).stream()
                    .sorted().collect(Collectors.joining(","));
            misc.line("контрольная сумма: %s", checksum(allIds));
            if (!points.isEmpty()) {
                String first = points.keySet().iterator().next();
                misc.line("достижимо из %s: %d из %d", first, reachable(first).size(), points.size());
                misc.line("инициалы: %s", points.values().stream().map(pt -> initials(pt.name()))
                        .collect(Collectors.joining(" ")));
            }
            misc.line("журнал: %d событий, %d символов, флаги %s", audit.size(), auditLength(), Long.toBinaryString(flags));
            StringJoiner recent = new StringJoiner(" | ");
            int shown = 0;
            for (String e : events) {
                if (shown++ == 3) {
                    break;
                }
                recent.add(e);
            }
            misc.line("последние: %s", recent);
            misc.line("сорванные: %s", failedSummary());
        }
        if (!errors.isEmpty()) {
            System.out.println("ошибок: " + errors.size());
        }
    }

    String failedSummary() {
        record Failure(String id, String reason) {
            String text() {
                return id + " (" + reason + ")";
            }
        }
        List<Failure> failures = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.status == Status.FAILED) {
                failures.add(new Failure(o.id, o.failure));
            }
        }
        failures.sort(Comparator.comparing(Failure::id));
        return failures.isEmpty() ? "нет" : failures.stream().map(Failure::text).collect(Collectors.joining(", "));
    }
}
