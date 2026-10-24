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
 *   MONTH <1..12>                         — текущий месяц для оборотов
 *   TERM <номер> <сумма> <ставка %> <мес>  — открыть срочный вклад со счёта
 *   SCORE <возраст> <доход в мес> <стаж лет> <просрочек> — кредитный скоринг
 *   SEARCH <номер> <от> <до>               — операции счёта в диапазоне сумм
 *   MONTHS                                 — обороты счёт × месяц
 *   FLAGS <номер>                          — флаги счёта в двоичном виде
 *   RATE <USD|EUR> <курс в рублях>         — новый курс валюты (с историей)
 *   RATES                                  — история курсов: мин / макс / среднее
 *   AUTOPAY <откуда> <куда> <сумма>        — ежемесячный автоплатёж, срабатывает при смене месяца
 *   COMPARE <сумма> <ставка %> <мес>       — аннуитет против дифференцированного платежа
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
            Integer idx = byNumber.get(number);
            if (idx != null) {
                turnover[idx][currentMonth - 1] += Math.abs(delta);
            }
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
    static int currentMonth = 1;
    /** Обороты: строка — счёт, столбец — месяц (сумма модулей операций в копейках). */
    static final long[][] turnover = new long[MAX_ACCOUNTS][12];
    static final List<TermDeposit> deposits = new ArrayList<>();

    /** Срочный вклад: деньги списаны со счёта, доход начисляется ежемесячной капитализацией. */
    static final class TermDeposit {
        final String account;
        final long principal;
        final double annualPercent;
        final int months;
        final int openedMonth;

        TermDeposit(String account, long principal, double annualPercent, int months, int openedMonth) {
            this.account = account;
            this.principal = principal;
            this.annualPercent = annualPercent;
            this.months = months;
            this.openedMonth = openedMonth;
        }

        long maturity() {
            return Math.round(compound(principal, annualPercent / 100.0 / 12.0, months));
        }
    }

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
                    case "MONTH":
                        setMonth(Integer.parseInt(t[1]));
                        break;
                    case "TERM":
                        openTerm(t[1], parseAmount(t[2]), Double.parseDouble(t[3]), Integer.parseInt(t[4]));
                        break;
                    case "SCORE":
                        score(Integer.parseInt(t[1]), parseAmount(t[2]), Integer.parseInt(t[3]), Integer.parseInt(t[4]));
                        break;
                    case "SEARCH":
                        search(t[1], parseAmount(t[2]), parseAmount(t[3]));
                        break;
                    case "MONTHS":
                        months();
                        break;
                    case "FLAGS":
                        flags(t[1]);
                        break;
                    case "RATE":
                        setRate(t[1], Double.parseDouble(t[2]));
                        break;
                    case "RATES":
                        rates();
                        break;
                    case "AUTOPAY":
                        autopay(t[1], t[2], parseAmount(t[3]));
                        break;
                    case "COMPARE":
                        compare(parseAmount(t[1]), Double.parseDouble(t[2]), Integer.parseInt(t[3]));
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
        printDeposits();
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

    // ---------------------------------------------------------------- вклады, скоринг, обороты

    static void setMonth(int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("месяц должен быть от 1 до 12");
        }
        if (month < currentMonth) {
            throw new IllegalArgumentException("нельзя вернуться в прошлое: сейчас месяц " + currentMonth);
        }
        while (currentMonth < month) {
            currentMonth++;
            runAutopays();
        }
        System.out.println("Текущий месяц: " + MONTH_NAMES[month - 1]);
    }

    static final String[] MONTH_NAMES = {"январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август",
            "сентябрь", "октябрь", "ноябрь", "декабрь"};

    /** Сложный процент рекурсией: P · (1 + r)^n. */
    static double compound(double principal, double monthlyRate, int months) {
        if (months == 0) {
            return principal;
        }
        return compound(principal * (1 + monthlyRate), monthlyRate, months - 1);
    }

    static void openTerm(String number, long amount, double annualPercent, int months) throws InsufficientFundsException {
        BankAccount acc = find(number);
        if (acc.currency() != 0) {
            throw new IllegalArgumentException("вклады открываются только в рублях");
        }
        if (months < 3 || months > 36) {
            throw new IllegalArgumentException("срок вклада от 3 до 36 месяцев");
        }
        if (annualPercent <= 0 || annualPercent > 25) {
            throw new IllegalArgumentException("ставка вне диапазона (0; 25] %");
        }
        acc.apply(-amount);
        TermDeposit d = new TermDeposit(number, amount, annualPercent, months, currentMonth);
        deposits.add(d);
        long income = d.maturity() - amount;
        System.out.printf(Locale.ROOT, "Вклад со счёта %s: %s на %d мес. под %.2f%% → доход %s (%.2f%% годовых эффективно)%n",
                number, formatMoney(amount, 0), months, annualPercent, formatMoney(income, 0),
                (Math.pow((double) d.maturity() / amount, 12.0 / months) - 1) * 100);
        journal.add("TERM " + number + " " + amount);
    }

    /**
     * Кредитный скоринг по баллам: возраст, доход, стаж, просрочки. Итоговая буква A..E.
     * Заложенные дефекты: «magic-number» (границы и баллы без имён), «deep-nesting».
     */
    static void score(int age, long incomeKopecks, int years, int overdue) {
        int[] ageBands = {21, 25, 35, 50, 65};
        int[] agePoints = {0, 10, 25, 30, 20, 5};
        int points = agePoints[ageBands.length];
        for (int i = 0; i < ageBands.length; i++) {
            if (age < ageBands[i]) {
                points = agePoints[i];
                break;
            }
        }
        long income = incomeKopecks / KOPECKS;
        if (income >= 150000) {
            points += 35;
        } else if (income >= 80000) {
            points += 25;
        } else if (income >= 40000) {
            points += 15;
        } else {
            points += 5;
        }
        points += Math.min(years, 10) * 2;
        if (overdue > 0) {
            if (overdue > 2) {
                if (overdue > 5) {
                    points -= 60;
                } else {
                    points -= 30;
                }
            } else {
                points -= 10 * overdue;
            }
        }
        points = Math.max(0, Math.min(100, points));
        char grade = (char) ('A' + Math.min(4, (100 - points) / 20));
        long limit = switch (grade) {
            case 'A' -> income * 12;
            case 'B' -> income * 8;
            case 'C' -> income * 4;
            case 'D' -> income;
            default -> 0;
        };
        System.out.printf("Скоринг: возраст %d, доход %s, стаж %d, просрочек %d → %d баллов, класс %c, лимит %s%n",
                age, formatMoney(incomeKopecks, 0), years, overdue, points, grade, formatMoney(limit * KOPECKS, 0));
    }

    /** Поиск операций в диапазоне сумм. Заложенный дефект «string-concat-in-loop». */
    static void search(String number, long from, long to) {
        BankAccount acc = find(number);
        if (from > to) {
            long tmp = from;
            from = to;
            to = tmp;
        }
        String found = "";
        int count = 0;
        for (int i = 0; i < acc.historySize; i++) {
            long abs = Math.abs(acc.history[i]);
            if (abs >= from && abs <= to) {
                found += "#" + (i + 1) + "(" + (acc.history[i] < 0 ? "-" : "+") + abs / KOPECKS + ") ";
                count++;
            }
        }
        System.out.println("Поиск по " + number + " от " + from / KOPECKS + " до " + to / KOPECKS + ": "
                + (count == 0 ? "ничего" : count + " — " + found.trim()));
    }

    /** Матрица оборотов счёт × месяц с итогами по строкам и столбцам. */
    static void months() {
        int lastMonth = 0;
        for (int i = 0; i < accountCount; i++) {
            for (int m = 0; m < 12; m++) {
                if (turnover[i][m] != 0) {
                    lastMonth = Math.max(lastMonth, m + 1);
                }
            }
        }
        if (lastMonth == 0) {
            System.out.println("Оборотов нет");
            return;
        }
        StringBuilder header = new StringBuilder(String.format("%-8s", "счёт"));
        for (int m = 0; m < lastMonth; m++) {
            header.append(String.format("%10s", MONTH_NAMES[m].substring(0, 3)));
        }
        header.append(String.format("%12s", "итого"));
        System.out.println(header);
        long[] columnTotals = new long[lastMonth];
        long max = 0;
        String maxCell = "-";
        for (int i = 0; i < accountCount; i++) {
            StringBuilder row = new StringBuilder(String.format("%-8s", accounts[i].number()));
            long rowTotal = 0;
            for (int m = 0; m < lastMonth; m++) {
                long v = turnover[i][m] / KOPECKS;
                row.append(String.format("%10d", v));
                rowTotal += v;
                columnTotals[m] += v;
                if (v > max) {
                    max = v;
                    maxCell = accounts[i].number() + "/" + MONTH_NAMES[m];
                }
            }
            row.append(String.format("%12d", rowTotal));
            System.out.println(row);
        }
        StringBuilder totals = new StringBuilder(String.format("%-8s", "итого"));
        long grand = 0;
        for (long c : columnTotals) {
            totals.append(String.format("%10d", c));
            grand += c;
        }
        totals.append(String.format("%12d", grand));
        System.out.println(totals);
        System.out.println("Максимальный оборот: " + maxCell + " (" + max + ")");
    }

    /** Флаги счёта и контрольная сумма номера: побитовые операции и char-арифметика. */
    static void flags(String number) {
        BankAccount acc = find(number);
        int f = acc.flags & 0xFF;
        String bits = String.format("%8s", Integer.toBinaryString(f)).replace(' ', '0');
        int digitSum = 0;
        int xor = 0;
        for (char ch : number.toCharArray()) {
            if (Character.isDigit(ch)) {
                digitSum += ch - '0';
                xor ^= ch - '0';
            }
        }
        System.out.println("Флаги " + number + ": " + bits + " (заморожен " + ((f & BankAccount.FROZEN) != 0)
                + ", VIP " + ((f & BankAccount.VIP) != 0) + "), сумма цифр " + digitSum + ", xor " + xor
                + ", чётность " + (Integer.bitCount(digitSum) % 2 == 0 ? "чёт" : "нечёт") + ", сдвиг " + (digitSum << 2));
    }

    static void printDeposits() {
        if (deposits.isEmpty()) {
            return;
        }
        long total = 0;
        long income = 0;
        System.out.println("Вклады:");
        for (TermDeposit d : deposits) {
            int closeMonth = d.openedMonth + d.months;
            String close = closeMonth <= 12 ? MONTH_NAMES[closeMonth - 1] : MONTH_NAMES[(closeMonth - 1) % 12] + " след. года";
            System.out.printf(Locale.ROOT, "  %-8s %s под %.2f%% на %d мес., к выплате %s (%s)%n", d.account,
                    formatMoney(d.principal, 0), d.annualPercent, d.months, formatMoney(d.maturity(), 0), close);
            total += d.principal;
            income += d.maturity() - d.principal;
        }
        System.out.println("  всего во вкладах " + formatMoney(total, 0) + ", ожидаемый доход " + formatMoney(income, 0));
    }

    // ---------------------------------------------------------------- курсы, автоплатежи, сравнение кредитов

    /** История курсов: по строке на валюту (USD, EUR), не больше 32 значений. */
    static final double[][] rateHistory = new double[2][32];
    static final int[] rateCount = new int[2];

    /** Платёжное поручение: что сделать при наступлении месяца. */
    interface Order {
        String describe();

        void execute() throws InsufficientFundsException;
    }

    static final List<Order> autopays = new ArrayList<>();

    static void setRate(String code, double rubles) {
        int currency = Arrays.asList(CURRENCIES).indexOf(code.toUpperCase(Locale.ROOT));
        if (currency <= 0) {
            throw new IllegalArgumentException("курс задаётся для USD или EUR, а не " + code);
        }
        if (rubles <= 0 || rubles > 10_000) {
            throw new IllegalArgumentException("подозрительный курс " + rubles);
        }
        double old = RATES[currency][0];
        RATES[currency][0] = rubles;
        RATES[0][currency] = 1.0 / rubles;
        RATES[1][2] = RATES[1][0] / RATES[2][0];
        RATES[2][1] = RATES[2][0] / RATES[1][0];
        int row = currency - 1;
        if (rateCount[row] == rateHistory[row].length) {
            System.arraycopy(rateHistory[row], 1, rateHistory[row], 0, rateHistory[row].length - 1);
            rateCount[row]--;
        }
        rateHistory[row][rateCount[row]++] = rubles;
        double change = (rubles - old) / old * 100;
        System.out.printf(Locale.ROOT, "Курс %s: %.2f → %.2f (%+.2f%%)%n", code.toUpperCase(Locale.ROOT), old, rubles, change);
    }

    static void rates() {
        for (int row = 0; row < 2; row++) {
            String code = CURRENCIES[row + 1];
            int n = rateCount[row];
            if (n == 0) {
                System.out.printf(Locale.ROOT, "%s: курс не менялся (%.2f)%n", code, RATES[row + 1][0]);
                continue;
            }
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                min = Math.min(min, rateHistory[row][i]);
                max = Math.max(max, rateHistory[row][i]);
                sum += rateHistory[row][i];
            }
            double[] sorted = Arrays.copyOf(rateHistory[row], n);
            Arrays.sort(sorted);
            double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
            System.out.printf(Locale.ROOT, "%s: %d изм., мин %.2f, макс %.2f, среднее %.2f, медиана %.2f, размах %.1f%%%n",
                    code, n, min, max, sum / n, median, (max - min) / min * 100);
        }
    }

    static void autopay(String from, String to, long amount) {
        BankAccount a = find(from);
        BankAccount b = find(to);
        int day = autopays.size() + 1;
        autopays.add(new Order() {
            @Override
            public String describe() {
                return "автоплатёж №" + day + " " + a.number() + " → " + b.number() + " " + formatMoney(amount, a.currency());
            }

            @Override
            public void execute() throws InsufficientFundsException {
                transfer(a.number(), b.number(), amount);
            }
        });
        System.out.println("Создан " + autopays.get(autopays.size() - 1).describe());
    }

    static void runAutopays() {
        for (Order o : autopays) {
            try {
                System.out.print("[" + MONTH_NAMES[currentMonth - 1] + "] ");
                o.execute();
            } catch (InsufficientFundsException e) {
                System.out.println("пропущен " + o.describe() + ": " + e.getMessage());
            } catch (IllegalStateException e) {
                System.out.println("пропущен " + o.describe() + ": " + e.getMessage());
            }
        }
    }

    /** Аннуитет против дифференцированного платежа: переплата и первый / последний платёж. */
    static void compare(long principal, double annualPercent, int months) {
        if (months <= 0 || months > 360) {
            throw new IllegalArgumentException("срок от 1 до 360 месяцев");
        }
        double r = annualPercent / 100 / 12;
        double annuity = r == 0 ? (double) principal / months
                : principal * r * Math.pow(1 + r, months) / (Math.pow(1 + r, months) - 1);
        double annuityTotal = annuity * months;
        double body = (double) principal / months;
        double diffTotal = 0;
        double first = 0;
        double last = 0;
        double rest = principal;
        for (int m = 1; m <= months; m++) {
            double payment = body + rest * r;
            if (m == 1) {
                first = payment;
            }
            if (m == months) {
                last = payment;
            }
            diffTotal += payment;
            rest -= body;
        }
        System.out.printf(Locale.ROOT, "Сравнение %s на %d мес. под %.1f%%:%n", formatMoney(principal, 0), months, annualPercent);
        System.out.printf(Locale.ROOT, "  аннуитет:          платёж %s, переплата %s%n",
                formatMoney(Math.round(annuity), 0), formatMoney(Math.round(annuityTotal) - principal, 0));
        System.out.printf(Locale.ROOT, "  дифференцированный: первый %s, последний %s, переплата %s%n",
                formatMoney(Math.round(first), 0), formatMoney(Math.round(last), 0),
                formatMoney(Math.round(diffTotal) - principal, 0));
        long saving = Math.round(annuityTotal - diffTotal);
        System.out.println("  выгоднее: " + (saving > 0 ? "дифференцированный, экономия " + formatMoney(saving, 0)
                : saving < 0 ? "аннуитет" : "одинаково"));
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
