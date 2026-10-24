import java.util.*;

/**
 * Демо-монолит «Склад»: приёмка, сканирование, движения остатков, сверка и выгрузка.
 * Это главный пример для показа пути «диагноз → исправление → проверка → ускорение»:
 * три горячих участка с квадратичной сложностью, каждый исправляется автоматически и проверяется quality gate.
 *
 *   1. {@link #reconcile}: {@code registered.contains(sku)} в цикле по сканам — O(n·m) → HashSet, O(n + m).
 *   2. {@link #exportCsv}: {@code csv += …} в цикле — O(n²) копирований → StringBuilder, O(n).
 *   3. {@link #movementTotals}: {@code movements.get(i)} у LinkedList — O(n²) → for-each, O(n).
 *
 * Команды:
 *   ITEM <sku> <зона> <цена> <название...>
 *   MOVE <sku> <±количество>
 *   SCAN <sku>
 *   AUDIT
 */
public class Main {

    static class Item {
        final String sku;
        final String zone;
        final int priceKopecks;
        final String title;
        int quantity;
        int scans;

        Item(String sku, String zone, int priceKopecks, String title) {
            this.sku = sku;
            this.zone = zone;
            this.priceKopecks = priceKopecks;
            this.title = title;
        }
    }

    static class WarehouseException extends Exception {
        WarehouseException(String message) {
            super(message);
        }
    }

    static final Map<String, Item> items = new LinkedHashMap<>();
    static final List<String> registered = new ArrayList<>();
    static final List<String> scans = new ArrayList<>();
    static final LinkedList<Integer> movements = new LinkedList<>();
    static int rejected;

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int lineNo = 0;
        while (in.hasNextLine()) {
            String line = in.nextLine().trim();
            lineNo++;
            if (line.isEmpty()) {
                continue;
            }
            try {
                handle(line.split(" "));
            } catch (WarehouseException | NumberFormatException e) {
                rejected++;
                if (rejected <= 5) {
                    System.err.println("строка " + lineNo + ": " + e.getMessage());
                }
            }
        }
        if (rejected > 5) {
            System.err.println("... и ещё ошибок: " + (rejected - 5));
        }
    }

    static void handle(String[] p) throws WarehouseException {
        switch (p[0]) {
            case "ITEM" -> {
                if (p.length < 5) {
                    throw new WarehouseException("ITEM: нужно sku, зона, цена и название");
                }
                if (items.containsKey(p[1])) {
                    throw new WarehouseException("товар " + p[1] + " уже зарегистрирован");
                }
                int price = Math.round(Float.parseFloat(p[3]) * 100);
                String title = String.join(" ", Arrays.copyOfRange(p, 4, p.length));
                items.put(p[1], new Item(p[1], p[2], price, title));
                registered.add(p[1]);
            }
            case "MOVE" -> {
                Item item = items.get(p[1]);
                if (item == null) {
                    throw new WarehouseException("движение по неизвестному товару " + p[1]);
                }
                int delta = Integer.parseInt(p[2]);
                if (item.quantity + delta < 0) {
                    throw new WarehouseException("остаток " + p[1] + " ушёл бы в минус");
                }
                item.quantity += delta;
                movements.add(delta);
            }
            case "SCAN" -> scans.add(p[1]);
            case "AUDIT" -> audit();
            default -> throw new WarehouseException("неизвестная команда " + p[0]);
        }
    }

    static void audit() {
        System.out.println("=== Сверка склада ===");
        System.out.println("Товаров: " + items.size() + ", сканов: " + scans.size() + ", движений: " + movements.size());
        reconcile();
        movementTotals();
        zones();
        exportCsv();
    }

    /** Горячий участок 1: поиск в списке внутри цикла. */
    static void reconcile() {
        int known = 0;
        int unknown = 0;
        Map<String, Integer> unknownSkus = new TreeMap<>();
        for (String sku : scans) {
            if (registered.contains(sku)) {
                known++;
            } else {
                unknown++;
                unknownSkus.merge(sku, 1, Integer::sum);
            }
        }
        for (String sku : scans) {
            Item item = items.get(sku);
            if (item != null) {
                item.scans++;
            }
        }
        int neverScanned = 0;
        for (Item item : items.values()) {
            if (item.scans == 0) {
                neverScanned++;
            }
        }
        System.out.println("Сканы: известных " + known + ", неизвестных " + unknown + ", не отсканировано товаров " + neverScanned);
        int shown = 0;
        for (Map.Entry<String, Integer> e : unknownSkus.entrySet()) {
            if (shown++ == 3) {
                System.out.println("  ...");
                break;
            }
            System.out.println("  неизвестный " + e.getKey() + " ×" + e.getValue());
        }
    }

    /** Горячий участок 3: индексный доступ к LinkedList. */
    static void movementTotals() {
        long incoming = 0;
        long outgoing = 0;
        int largest = 0;
        for (int i = 0; i < movements.size(); i++) {
            int delta = movements.get(i);
            if (delta > 0) {
                incoming += delta;
            } else {
                outgoing -= delta;
            }
            largest = Math.max(largest, Math.abs(delta));
        }
        System.out.println("Движения: приход " + incoming + ", расход " + outgoing + ", крупнейшее " + largest);
    }

    static void zones() {
        Map<String, long[]> byZone = new TreeMap<>();
        for (Item item : items.values()) {
            long[] acc = byZone.computeIfAbsent(item.zone, z -> new long[2]);
            acc[0] += item.quantity;
            acc[1] += (long) item.quantity * item.priceKopecks;
        }
        System.out.println("Зоны:");
        for (Map.Entry<String, long[]> e : byZone.entrySet()) {
            System.out.printf("  %-4s %8d шт. %14.2f руб.%n", e.getKey(), e.getValue()[0], e.getValue()[1] / 100.0);
        }
    }

    /** Горячий участок 2: конкатенация строки в цикле. */
    static void exportCsv() {
        String csv = "";
        for (Item item : items.values()) {
            csv += item.sku + ";" + item.zone + ";" + item.quantity + ";" + item.priceKopecks + "\n";
        }
        int checksum = 0;
        for (int i = 0; i < csv.length(); i++) {
            checksum = 31 * checksum + csv.charAt(i);
        }
        String[] rows = csv.split("\n");
        System.out.println("Выгрузка CSV: " + (csv.isEmpty() ? 0 : rows.length) + " строк, " + csv.length()
                + " символов, контрольная сумма " + Integer.toHexString(checksum));
        if (!csv.isEmpty()) {
            System.out.println("  первая строка: " + rows[0]);
            System.out.println("  последняя строка: " + rows[rows.length - 1]);
        }
    }
}
