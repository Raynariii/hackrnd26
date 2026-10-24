import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

/**
 * Монолит «Банк»: счета, переводы, проценты, кредитный калькулятор, проверка номеров карт, выписки.
 * Акцент — язык и java.lang (примитивы, обёртки, массивы, арифметика, приведение, Math, String, Object, System)
 * и консольный ввод-вывод (Scanner, printf, String.format, Formatter, StandardCharsets, System.err, код выхода).
 *
 * Команды:
 *   OPEN <номер> <валюта RUB|USD|EUR> <владелец>
 *   DEPOSIT <номер> <сумма>
 *   WITHDRAW <номер> <сумма>
 *   TRANSFER <откуда> <куда> <сумма>
 *   INTEREST <годовая ставка %> <месяцев>
 *   LOAN <сумма> <ставка %> <месяцев>
 *   CARD <номер карты>
 *   STATEMENT <номер>
 *   HISTOGRAM
 *   QUIT [код]
 */
public class Main {

    static final int MAX_ACCOUNTS = 64;
    static final long KOPECKS = 100L;
    static final String[] CURRENCIES = {"RUB", "USD", "EUR"};
    static final double[][] RATES = {
            // в RUB, USD, EUR
            {1.0, 0.0105, 0.0097},   // из RUB
            {95.0, 1.0, 0.92},       // из USD
            {103.0, 1.087, 1.0},     // из EUR
    };
    static final short MAX_HISTORY;
    static final char SEPARATOR = '|';

    static {
        MAX_HISTORY = (short) 200;
    }

    // ---------------------------------------------------------------- модели

    interface Account {
        String number();

        long balance();

        int currency();

        default String formatted() {
            return Main.formatMoney(balance(), currency());
        }
    }

    static final class BankAccount implements Account {
        private final String number;
        private final String owner;
        private final int currency;
        private long balance;
        private final long[] history = new long[MAX_HISTORY];
        private int historySize;
        private byte flags;

        static final byte FROZEN = 1;
        static final byte VIP = 1 << 1;

        BankAccount(String number, String owner, int currency) {
            this.number = number;
            this.owner = owner;
            this.currency = currency;
        }

        @Override
        public String number() {
            return number;
        }

        @Override
        public long balance() {
            return balance;
        }

        @Override
        public int currency() {
            return currency;
        }

        boolean isFrozen() {
            return (flags & FROZEN) != 0;
        }

        void record(long delta) {
            if (historySize == history.length) {
                System.arraycopy(history, 1, history, 0, history.length - 1);
                historySize--;
            }
            history[historySize++] = delta;
        }

        void apply(long delta) throws InsufficientFundsException {
            if (isFrozen()) {
                throw new IllegalStateException("счёт " + number + " заморожен");
            }
            if (balance + delta < 0) {
                throw new InsufficientFundsException(this, -delta);
            }
            balance = Math.addExact(balance, delta);
            record(delta);
            if (balance >= 10_000_000L * KOPECKS) {
                flags |= VIP;
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof BankAccount other && other.number.equals(number);
        }

        @Override
        public int hashCode() {
            return number.hashCode();
        }

        @Override
        public String toString() {
            return "Счёт[" + number + ", " + owner + ", " + formatted() + "]";
        }
    }

    // ---------------------------------------------------------------- исключения

    static class InsufficientFundsException extends Exception {
        final long missing;

        InsufficientFundsException(BankAccount account, long requested) {
            super("на счёте " + account.number() + " " + account.formatted() + ", а нужно "
                    + formatMoney(requested, account.currency()));
            this.missing = requested - account.balance();
        }
    }

    static class InvalidAmountException extends RuntimeException {
        InvalidAmountException(String raw) {
            super("некорректная сумма: «" + raw + "»");
        }
    }

    // ---------------------------------------------------------------- состояние

    static final BankAccount[] accounts = new BankAccount[MAX_ACCOUNTS];
    static int accountCount;
    static final Map<String, Integer> byNumber = new HashMap<>();
    static final List<String> journal = new ArrayList<>();
    static long totalFeesKopecks;
    static int commandCount;

    // ---------------------------------------------------------------- точка входа

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in, StandardCharsets.UTF_8);
        int exitCode = 0;
        loop:
        while (in.hasNextLine()) {
            String line = in.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }
            commandCount++;
            String[] t = line.split("\\s+");
            try {
                switch (t[0].toUpperCase(Locale.ROOT)) {
                    case "OPEN":
                        open(t[1], t[2], joinFrom(t, 3));
                        break;
                    case "DEPOSIT":
                        deposit(t[1], parseAmount(t[2]));
                        break;
                    case "WITHDRAW":
                        withdraw(t[1], parseAmount(t[2]));
                        break;
                    case "TRANSFER":
                        transfer(t[1], t[2], parseAmount(t[3]));
                        break;
                    case "INTEREST":
                        interest(Double.parseDouble(t[1]), Integer.parseInt(t[2]));
                        break;
                    case "LOAN":
                        loan(parseAmount(t[1]), Double.parseDouble(t[2]), Integer.parseInt(t[3]));
                        break;
                    case "CARD":
                        card(joinFrom(t, 1));
                        break;
                    case "STATEMENT":
                        statement(t[1]);
                        break;
                    case "HISTOGRAM":
                        histogram();
                        break;
                    case "FREEZE":
                        find(t[1]).flags |= BankAccount.FROZEN;
                        break;
                    case "QUIT":
                        exitCode = t.length > 1 ? Integer.parseInt(t[1]) : 0;
                        break loop;
                    default:
                        System.err.println("Неизвестная команда: " + t[0]);
                        exitCode = 2;
                }
            } catch (InsufficientFundsException e) {
                System.out.println("ОТКАЗ: " + e.getMessage() + " (не хватает " + formatMoney(e.missing, 0) + ")");
            } catch (ArrayIndexOutOfBoundsException e) {
                System.err.println("Не хватает аргументов в команде: " + line);
                exitCode = 2;
            } catch (InvalidAmountException | IllegalStateException | IllegalArgumentException e) {
                System.out.println("ОШИБКА: " + e.getMessage());
            }
        }
        summary();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    // ---------------------------------------------------------------- операции

    static void open(String number, String currencyCode, String owner) {
        if (accountCount == MAX_ACCOUNTS) {
            throw new IllegalStateException("достигнут лимит счетов: " + MAX_ACCOUNTS);
        }
        if (byNumber.containsKey(number)) {
            throw new IllegalArgumentException("счёт " + number + " уже существует");
        }
        int currency = Arrays.asList(CURRENCIES).indexOf(currencyCode.toUpperCase(Locale.ROOT));
        if (currency < 0) {
            throw new IllegalArgumentException("неизвестная валюта " + currencyCode);
        }
        if (owner.isBlank()) {
            throw new IllegalArgumentException("не указан владелец");
        }
        BankAccount acc = new BankAccount(number, owner, currency);
        accounts[accountCount] = acc;
        byNumber.put(number, accountCount);
        accountCount++;
        int bytes = owner.getBytes(StandardCharsets.UTF_8).length;
        System.out.printf("Открыт %s (владелец: %s, %d симв., %d байт UTF-8)%n", acc, owner, owner.length(), bytes);
        journal.add("OPEN " + number);
    }

    static void deposit(String number, long amount) throws InsufficientFundsException {
        BankAccount acc = find(number);
        acc.apply(amount);
        System.out.println("Пополнение " + number + ": +" + formatMoney(amount, acc.currency()) + " → " + acc.formatted());
        journal.add("DEPOSIT " + number + " " + amount);
    }

    static void withdraw(String number, long amount) throws InsufficientFundsException {
        BankAccount acc = find(number);
        long fee = commission(amount, acc.currency());
        acc.apply(-(amount + fee));
        totalFeesKopecks += fee;
        System.out.println("Снятие " + number + ": -" + formatMoney(amount, acc.currency())
                + " (комиссия " + formatMoney(fee, acc.currency()) + ") → " + acc.formatted());
        journal.add("WITHDRAW " + number + " " + amount);
    }

    static void transfer(String from, String to, long amount) throws InsufficientFundsException {
        BankAccount a = find(from);
        BankAccount b = find(to);
        if (a.equals(b)) {
            throw new IllegalArgumentException("перевод самому себе");
        }
        long converted = convert(amount, a.currency(), b.currency());
        a.apply(-amount);
        try {
            b.apply(converted);
        } catch (IllegalStateException e) {
            a.apply(amount); // откат списания: получатель заморожен
            throw e;
        } finally {
            journal.add("TRANSFER " + from + "->" + to + " " + amount);
        }
        System.out.println("Перевод " + from + " → " + to + ": " + formatMoney(amount, a.currency())
                + (a.currency() != b.currency() ? " = " + formatMoney(converted, b.currency()) : ""));
    }

    /** Ежемесячная капитализация процентов на все рублёвые счета. */
    static void interest(double annualPercent, int months) {
        if (months <= 0 || months > 120) {
            throw new IllegalArgumentException("число месяцев вне диапазона 1..120");
        }
        double monthly = annualPercent / 100.0 / 12.0;
        for (int i = 0; i < accountCount; i++) {
            BankAccount acc = accounts[i];
            if (acc.currency() != 0 || acc.isFrozen()) {
                continue;
            }
            long before = acc.balance();
            double value = before;
            int m = 0;
            do {
                value = value * (1 + monthly);
                m++;
            } while (m < months);
            long earned = Math.round(value) - before;
            if (earned > 0) {
                try {
                    acc.apply(earned);
                } catch (InsufficientFundsException e) {
                    throw new IllegalStateException("невозможно: начисление отрицательное", e);
                }
            }
            System.out.printf("Проценты %s: %+.2f за %d мес.%n", acc.number(), earned / 100.0, months);
        }
    }

    /** Аннуитетный платёж + таблица первых месяцев. Заложенный дефект «long-method» и «deep-nesting». */
    static void loan(long principal, double annualPercent, int months) {
        if (months <= 0) {
            throw new IllegalArgumentException("срок кредита должен быть положительным");
        }
        double r = annualPercent / 100 / 12;
        double payment;
        if (r == 0) {
            payment = (double) principal / months;
        } else {
            payment = principal * r * Math.pow(1 + r, months) / (Math.pow(1 + r, months) - 1);
        }
        long paymentK = (long) Math.ceil(payment);
        System.out.printf("Кредит %s на %d мес. под %.1f%%: платёж %s, переплата %s%n",
                formatMoney(principal, 0), months, annualPercent, formatMoney(paymentK, 0),
                formatMoney(paymentK * months - principal, 0));
        double rest = principal;
        System.out.println(" мес |    платёж |  проценты |      долг");
        for (int m = 1; m <= months; m++) {
            double interestPart = rest * r;
            double body = payment - interestPart;
            rest -= body;
            if (rest < 0) {
                rest = 0;
            }
            if (m <= 3 || m == months) {
                if (months > 4) {
                    if (m == months) {
                        if (months > 5) {
                            System.out.println(" ... |           |           |");
                        }
                    }
                }
                System.out.printf("%4d | %9.2f | %9.2f | %9.2f%n", m, payment / 100, interestPart / 100, rest / 100);
            }
        }
        int years = months / 12;
        int restMonths = months % 12;
        String term = years > 0 ? years + " г. " : "";
        term += restMonths > 0 ? restMonths + " мес." : "";
        System.out.println("Срок: " + term.trim());
    }

    /** Проверка номера карты по алгоритму Луна, определение платёжной системы. */
    static void card(String raw) {
        String digits = raw.replaceAll("[\\s-]", "");
        if (!digits.chars().allMatch(Character::isDigit) || digits.length() < 12 || digits.length() > 19) {
            System.out.println("Карта " + raw + ": неверный формат");
            return;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        boolean valid = sum % 10 == 0;
        char first = digits.charAt(0);
        String system = switch (first) {
            case '2' -> "МИР";
            case '4' -> "VISA";
            case '5' -> "Mastercard";
            case '3' -> digits.startsWith("34") || digits.startsWith("37") ? "AmEx" : "JCB";
            default -> "неизвестно";
        };
        String masked = digits.substring(0, 4) + " **** **** " + digits.substring(digits.length() - 4);
        System.out.println("Карта " + masked + ": " + (valid ? "номер корректен" : "ошибка контрольной суммы")
                + ", система " + system);
    }

    static void statement(String number) {
        BankAccount acc = find(number);
        System.out.println("Выписка " + acc);
        long running = 0;
        long in = 0;
        long out = 0;
        // заложенный дефект «string-concat-in-loop»
        String line = "";
        for (int i = 0; i < acc.historySize; i++) {
            long d = acc.history[i];
            running += d;
            if (d >= 0) {
                in += d;
            } else {
                out -= d;
            }
            line += (d >= 0 ? "+" : "") + d / KOPECKS + " ";
        }
        System.out.println("  движения: " + (line.isEmpty() ? "нет" : line.trim()));
        System.out.println("  приход " + formatMoney(in, acc.currency()) + ", расход " + formatMoney(out, acc.currency())
                + ", сальдо " + formatMoney(running, acc.currency()));
        long[] sorted = Arrays.copyOf(acc.history, acc.historySize);
        Arrays.sort(sorted);
        if (sorted.length > 0) {
            long median = sorted.length % 2 == 1 ? sorted[sorted.length / 2]
                    : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
            System.out.println("  медиана операции: " + formatMoney(median, acc.currency()));
        }
    }

    /** Гистограмма остатков ASCII-графикой. */
    static void histogram() {
        long max = 1;
        for (int i = 0; i < accountCount; i++) {
            max = Math.max(max, toRub(accounts[i]));
        }
        System.out.println("Остатки в рублях:");
        for (int i = 0; i < accountCount; i++) {
            long rub = toRub(accounts[i]);
            int width = (int) (rub * 30 / max);
            System.out.printf("  %-8s %s %s%n", accounts[i].number(), "#".repeat(width), formatMoney(rub, 0));
        }
    }

    static void summary() {
        System.out.println("---- Итоги ----");
        long total = 0;
        int vip = 0;
        int frozen = 0;
        for (int i = 0; i < accountCount; i++) {
            total += toRub(accounts[i]);
            if ((accounts[i].flags & BankAccount.VIP) != 0) {
                vip++;
            }
            if (accounts[i].isFrozen()) {
                frozen++;
            }
        }
        Formatter f = new Formatter(new StringBuilder(), Locale.ROOT);
        f.format("Счетов: %d (VIP %d, заморожено %d), всего в рублях: %s, комиссий: %s, команд: %d",
                accountCount, vip, frozen, formatMoney(total, 0), formatMoney(totalFeesKopecks, 0), commandCount);
        System.out.println(f.toString());
        System.out.println("Подозрительные пары: " + suspiciousPairs());
        Integer a = 127;
        Integer b = 127;
        Integer big = Integer.valueOf(journal.size());
        System.out.println("Журнал: " + big + " записей, кэш Integer: " + (a == b) + ", сравнение: "
                + Integer.compare(big, 10) + ", hex: " + Integer.toHexString(big) + ", макс long: " + Long.MAX_VALUE);
    }

    /**
     * Пары счетов одного владельца с почти одинаковым остатком — двойной цикл по массиву.
     * Заложенный дефект «nested-loop-same-collection».
     */
    static String suspiciousPairs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < accountCount; i++) {
            for (int j = 0; j < accountCount; j++) {
                if (i >= j) {
                    continue;
                }
                BankAccount x = accounts[i];
                BankAccount y = accounts[j];
                if (x.owner.equalsIgnoreCase(y.owner) && Math.abs(toRub(x) - toRub(y)) < 100 * KOPECKS) {
                    sb.append(x.number()).append(SEPARATOR).append(y.number()).append(' ');
                }
            }
        }
        return sb.length() == 0 ? "нет" : sb.toString().trim();
    }

    // ---------------------------------------------------------------- вспомогательное

    static BankAccount find(String number) {
        Integer idx = byNumber.get(number);
        if (idx == null) {
            throw new IllegalArgumentException("нет счёта " + number);
        }
        return accounts[idx];
    }

    static long parseAmount(String raw) {
        try {
            double value = Double.parseDouble(raw.replace(',', '.'));
            if (value <= 0 || Double.isNaN(value) || Double.isInfinite(value)) {
                throw new InvalidAmountException(raw);
            }
            return Math.round(value * KOPECKS);
        } catch (NumberFormatException e) {
            throw new InvalidAmountException(raw);
        }
    }

    /** Комиссия за снятие: заложенный дефект «magic-number» (1.5 %, минимум 50 ₽, бесплатно до 3000 ₽). */
    static long commission(long amount, int currency) {
        if (currency != 0) {
            return Math.max(amount * 2 / 100, 100);
        }
        if (amount <= 300000) {
            return 0;
        }
        return Math.max((long) (amount * 0.015), 5000);
    }

    static long convert(long amount, int from, int to) {
        return Math.round(amount * RATES[from][to]);
    }

    static long toRub(BankAccount acc) {
        return convert(acc.balance(), acc.currency(), 0);
    }

    static String formatMoney(long kopecks, int currency) {
        String sign = kopecks < 0 ? "-" : "";
        long abs = Math.abs(kopecks);
        String symbol = switch (currency) {
            case 1 -> "$";
            case 2 -> "€";
            default -> "₽";
        };
        return String.format(Locale.ROOT, "%s%,d.%02d %s", sign, abs / KOPECKS, abs % KOPECKS, symbol);
    }

    static String joinFrom(String[] parts, int from) {
        if (from >= parts.length) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(parts, from, parts.length));
    }
}
