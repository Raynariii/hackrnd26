import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Монолит «Онлайн-курс»: студенты, группы, задания разных типов, оценки, дедлайны, рейтинг, итоговые отметки.
 * Акцент — бонусный scope: record (с компактным конструктором), enum с полями и абстрактными методами,
 * sealed-интерфейс + switch с шаблонами, дженерики (свой репозиторий с ограничениями, wildcard), лямбды
 * и java.util.function, Stream API и Collectors, Optional, var, switch-выражения, текстовые блоки.
 *
 * Команды:
 *   STUDENT <id> <группа> <имя>
 *   TASK <id> <тип: QUIZ|LAB|PROJECT|EXAM> <макс. балл> <дедлайн-день> <название...>
 *   SUBMIT <студент> <задание> <балл> <день>
 *   BONUS <студент> <баллы> <причина...>
 *   REPORT [группа]
 */
public class Main {

    // ---------------------------------------------------------------- модель: records, enum, sealed

    interface Identifiable {
        String id();
    }

    record Student(String id, String group, String name) implements Identifiable {
        Student {
            Objects.requireNonNull(id);
            if (!group.matches("[А-ЯA-Z]{2,4}-\\d{2}")) {
                throw new IllegalArgumentException("группа должна быть вида ИВТ-21, а не " + group);
            }
        }
    }

    enum TaskType {
        QUIZ("тест", 0.10) {
            @Override
            double latePenalty(int daysLate) {
                return daysLate > 0 ? 1.0 : 0.0; // тест после дедлайна не принимается
            }
        },
        LAB("лабораторная", 0.30) {
            @Override
            double latePenalty(int daysLate) {
                return Math.min(0.5, daysLate * 0.1);
            }
        },
        PROJECT("проект", 0.35) {
            @Override
            double latePenalty(int daysLate) {
                return Math.min(0.3, daysLate * 0.05);
            }
        },
        EXAM("экзамен", 0.25) {
            @Override
            double latePenalty(int daysLate) {
                return daysLate > 0 ? 1.0 : 0.0;
            }
        };

        final String title;
        final double weight;

        TaskType(String title, double weight) {
            this.title = title;
            this.weight = weight;
        }

        abstract double latePenalty(int daysLate);
    }

    record Task(String id, TaskType type, int maxScore, int deadline, String title) implements Identifiable {
        Task {
            if (maxScore <= 0) {
                throw new IllegalArgumentException("максимальный балл должен быть положительным");
            }
        }
    }

    /** Событие журнала: закрытая иерархия — компилятор проверяет полноту switch. */
    sealed interface Event permits Submitted, Rejected, Bonus {
        String studentId();
    }

    record Submitted(String studentId, String taskId, int raw, double counted, int daysLate) implements Event {
    }

    record Rejected(String studentId, String taskId, String reason) implements Event {
    }

    record Bonus(String studentId, int points, String reason) implements Event {
    }

    enum Mark {
        EXCELLENT("отлично", 85), GOOD("хорошо", 70), SATISFACTORY("удовлетворительно", 55), FAIL("неудовлетворительно", 0);

        final String text;
        final int threshold;

        Mark(String text, int threshold) {
            this.text = text;
            this.threshold = threshold;
        }

        static Mark of(double percent) {
            return Arrays.stream(values()).filter(m -> percent >= m.threshold).findFirst().orElse(FAIL);
        }
    }

    record Pair<A, B>(A first, B second) {
    }

    // ---------------------------------------------------------------- дженерик-репозиторий

    static final class Repository<T extends Identifiable> implements Iterable<T> {
        private final Map<String, T> items = new LinkedHashMap<>();
        private final String kind;

        Repository(String kind) {
            this.kind = kind;
        }

        void add(T item) {
            if (items.putIfAbsent(item.id(), item) != null) {
                throw new IllegalArgumentException(kind + " " + item.id() + " уже существует");
            }
        }

        Optional<T> find(String id) {
            return Optional.ofNullable(items.get(id));
        }

        T require(String id) {
            return find(id).orElseThrow(() -> new NoSuchElementException("нет такого: " + kind + " " + id));
        }

        List<T> where(Predicate<? super T> condition) {
            return items.values().stream().filter(condition).collect(Collectors.toList());
        }

        <R> List<R> map(Function<? super T, ? extends R> f) {
            List<R> result = new ArrayList<>();
            for (T t : items.values()) {
                result.add(f.apply(t));
            }
            return result;
        }

        int size() {
            return items.size();
        }

        Stream<T> stream() {
            return items.values().stream();
        }

        @Override
        public Iterator<T> iterator() {
            return Collections.unmodifiableCollection(items.values()).iterator();
        }
    }

    /** Обобщённые утилиты с ограниченными типами. */
    static <T extends Comparable<? super T>> T maxOf(Collection<? extends T> values, T fallback) {
        T best = null;
        for (T v : values) {
            if (best == null || v.compareTo(best) > 0) {
                best = v;
            }
        }
        return best == null ? fallback : best;
    }

    static <K, V extends Number> double sumValues(Map<K, V> map) {
        return map.values().stream().mapToDouble(Number::doubleValue).sum();
    }

    // ---------------------------------------------------------------- состояние

    static final Repository<Student> students = new Repository<>("студент");
    static final Repository<Task> tasks = new Repository<>("задание");
    static final List<Event> journal = new ArrayList<>();
    static final Map<String, BiConsumer<String[], Integer>> handlers = new LinkedHashMap<>();
    static int errors;

    static {
        handlers.put("STUDENT", (p, line) -> students.add(new Student(p[1], p[2], tail(p, 3))));
        handlers.put("TASK", (p, line) -> tasks.add(new Task(p[1], TaskType.valueOf(p[2]),
                Integer.parseInt(p[3]), Integer.parseInt(p[4]), tail(p, 5))));
        handlers.put("SUBMIT", (p, line) -> submit(p[1], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4])));
        handlers.put("BONUS", (p, line) -> {
            students.require(p[1]);
            journal.add(new Bonus(p[1], Integer.parseInt(p[2]), tail(p, 3)));
        });
        handlers.put("REPORT", (p, line) -> report(p.length > 1 ? Optional.of(p[1]) : Optional.empty()));
    }

    public static void main(String[] args) {
        var in = new Scanner(System.in);
        int lineNo = 0;
        while (in.hasNextLine()) {
            lineNo++;
            var line = in.nextLine().trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            var parts = line.split("\\s+");
            var handler = handlers.get(parts[0].toUpperCase());
            if (handler == null) {
                fail(lineNo, "неизвестная команда " + parts[0]);
                continue;
            }
            try {
                handler.accept(parts, lineNo);
            } catch (ArrayIndexOutOfBoundsException e) {
                fail(lineNo, "не хватает аргументов");
            } catch (IllegalArgumentException | NoSuchElementException e) {
                fail(lineNo, e.getMessage());
            }
        }
        if (errors > 0) {
            System.err.println("Строк с ошибками: " + errors);
        }
    }

    static void fail(int lineNo, String message) {
        errors++;
        System.err.println("строка " + lineNo + ": " + message);
    }

    static String tail(String[] parts, int from) {
        return Arrays.stream(parts).skip(from).collect(Collectors.joining(" "));
    }

    // ---------------------------------------------------------------- логика

    static void submit(String studentId, String taskId, int raw, int day) {
        Student s = students.require(studentId);
        Task t = tasks.require(taskId);
        if (raw < 0 || raw > t.maxScore()) {
            journal.add(new Rejected(s.id(), t.id(), "балл " + raw + " вне диапазона 0.." + t.maxScore()));
            return;
        }
        int daysLate = Math.max(0, day - t.deadline());
        double penalty = t.type().latePenalty(daysLate);
        if (penalty >= 1.0) {
            journal.add(new Rejected(s.id(), t.id(), t.type().title + " сдан(а) после дедлайна на " + daysLate + " дн."));
            return;
        }
        double counted = raw * (1 - penalty);
        // повторная сдача: засчитывается лучшая попытка
        Optional<Submitted> previous = submissionsOf(s.id()).stream()
                .filter(x -> x.taskId().equals(t.id()))
                .max(Comparator.comparingDouble(Submitted::counted));
        if (previous.isPresent() && previous.get().counted() >= counted) {
            journal.add(new Rejected(s.id(), t.id(), "попытка хуже предыдущей"));
            return;
        }
        journal.add(new Submitted(s.id(), t.id(), raw, counted, daysLate));
    }

    static List<Submitted> submissionsOf(String studentId) {
        List<Submitted> result = new ArrayList<>();
        for (Event e : journal) {
            if (e instanceof Submitted sub && sub.studentId().equals(studentId)) {
                result.add(sub);
            }
        }
        return result;
    }

    /** Лучший засчитанный балл по каждому заданию студента. */
    static Map<String, Double> bestScores(String studentId) {
        return submissionsOf(studentId).stream()
                .collect(Collectors.toMap(Submitted::taskId, Submitted::counted, Math::max, TreeMap::new));
    }

    /** Итог в процентах: взвешенно по типам заданий + бонусы (не больше 10). */
    static double finalPercent(Student s) {
        Map<String, Double> best = bestScores(s.id());
        Map<TaskType, List<Task>> byType = tasks.stream().collect(Collectors.groupingBy(Task::type,
                () -> new EnumMap<>(TaskType.class), Collectors.toList()));
        double total = 0;
        double usedWeight = 0;
        for (var entry : byType.entrySet()) {
            int max = entry.getValue().stream().mapToInt(Task::maxScore).sum();
            double got = entry.getValue().stream().mapToDouble(t -> best.getOrDefault(t.id(), 0.0)).sum();
            total += entry.getKey().weight * (got / max);
            usedWeight += entry.getKey().weight;
        }
        double percent = usedWeight == 0 ? 0 : total / usedWeight * 100;
        int bonus = journal.stream()
                .filter(e -> e instanceof Bonus b && b.studentId().equals(s.id()))
                .mapToInt(e -> ((Bonus) e).points())
                .sum();
        return Math.min(100, percent + Math.min(10, bonus));
    }

    // ---------------------------------------------------------------- отчёт

    static void report(Optional<String> group) {
        Predicate<Student> inGroup = group.<Predicate<Student>>map(g -> s -> s.group().equals(g)).orElse(s -> true);
        List<Student> selected = students.where(inGroup);
        String header = """
                ====================================
                  Отчёт по курсу%s
                  студентов: %d, заданий: %d, событий: %d
                ====================================""".formatted(group.map(g -> " (группа " + g + ")").orElse(""),
                selected.size(), tasks.size(), journal.size());
        System.out.println(header);
        if (selected.isEmpty()) {
            System.out.println("Нет студентов.");
            return;
        }
        printRanking(selected);
        printByMark(selected);
        printTaskStats(selected);
        printLate(selected);
        printGroups();
        printEvents(selected);
        printTopPairs(selected);
    }

    static void printRanking(List<Student> selected) {
        System.out.println("Рейтинг:");
        // заложенный дефект: finalPercent пересчитывается внутри компаратора — O(n log n · события)
        List<Student> ranked = selected.stream()
                .sorted(Comparator.comparingDouble((Student s) -> finalPercent(s)).reversed().thenComparing(Student::id))
                .toList();
        IntStream.range(0, ranked.size()).forEach(i -> {
            Student s = ranked.get(i);
            double p = finalPercent(s);
            System.out.printf("  %2d. %-10s %-7s %6.2f%%  %s%n", i + 1, s.name(), s.group(), p, Mark.of(p).text);
        });
    }

    static void printByMark(List<Student> selected) {
        Map<Mark, List<String>> byMark = selected.stream().collect(Collectors.groupingBy(
                s -> Mark.of(finalPercent(s)), () -> new EnumMap<>(Mark.class),
                Collectors.mapping(Student::name, Collectors.toList())));
        System.out.println("По отметкам:");
        byMark.forEach((mark, names) -> System.out.println("  " + mark.text + ": " + String.join(", ", names)));
        Map<Boolean, Long> passed = selected.stream()
                .collect(Collectors.partitioningBy(s -> Mark.of(finalPercent(s)) != Mark.FAIL, Collectors.counting()));
        System.out.printf("  сдали %d из %d%n", passed.get(true), selected.size());
    }

    /**
     * Статистика по заданиям. Заложенный дефект «collection-lookup-in-loop»: List.contains по id выбранных
     * студентов на каждом событии журнала.
     */
    static void printTaskStats(List<Student> selected) {
        List<String> ids = selected.stream().map(Student::id).collect(Collectors.toList());
        System.out.println("Задания:");
        for (Task t : tasks) {
            List<Double> scores = new ArrayList<>();
            for (Event e : journal) {
                if (e instanceof Submitted s && s.taskId().equals(t.id()) && ids.contains(s.studentId())) {
                    scores.add(s.counted());
                }
            }
            DoubleSummaryStatistics st = scores.stream().mapToDouble(Double::doubleValue).summaryStatistics();
            String line = scores.isEmpty() ? "нет сдач"
                    : String.format("сдач %d, среднее %.1f, мин %.1f, макс %.1f из %d",
                    st.getCount(), st.getAverage(), st.getMin(), st.getMax(), t.maxScore());
            System.out.printf("  %-6s %-13s %-25s %s%n", t.id(), t.type().title, t.title(), line);
        }
    }

    static void printLate(List<Student> selected) {
        Set<String> ids = selected.stream().map(Student::id).collect(Collectors.toSet());
        Map<String, Long> late = journal.stream()
                .filter(e -> ids.contains(e.studentId()))
                .filter(e -> switch (e) {
                    case Submitted s -> s.daysLate() > 0;
                    case Rejected r -> r.reason().contains("дедлайн");
                    case Bonus b -> false;
                })
                .collect(Collectors.groupingBy(Event::studentId, TreeMap::new, Collectors.counting()));
        System.out.println("Опоздания: " + (late.isEmpty() ? "нет" : late));
    }

    static void printGroups() {
        Map<String, Double> avgByGroup = students.stream().collect(Collectors.groupingBy(Student::group, TreeMap::new,
                Collectors.averagingDouble(Main::finalPercent)));
        System.out.println("Средний итог по группам:");
        avgByGroup.forEach((g, avg) -> System.out.printf("  %s: %.2f%%%n", g, avg));
        String best = maxOf(avgByGroup.entrySet().stream()
                .map(e -> String.format("%06.2f %s", e.getValue(), e.getKey())).toList(), "—");
        System.out.println("  лучшая: " + best.substring(best.indexOf(' ') + 1)
                + String.format(" (сумма средних %.2f)", sumValues(avgByGroup)));
    }

    static void printEvents(List<Student> selected) {
        Set<String> ids = selected.stream().map(Student::id).collect(Collectors.toSet());
        Function<Event, String> describe = e -> switch (e) {
            case Submitted s -> {
                String late = s.daysLate() > 0 ? ", опоздание " + s.daysLate() + " дн." : "";
                yield s.studentId() + " сдал " + s.taskId() + " на " + s.raw() + late;
            }
            case Rejected r -> r.studentId() + ": отказ по " + r.taskId() + " — " + r.reason();
            case Bonus b -> b.studentId() + ": бонус +" + b.points() + " (" + b.reason() + ")";
        };
        List<String> lastEvents = journal.stream().filter(e -> ids.contains(e.studentId())).map(describe).toList();
        int from = Math.max(0, lastEvents.size() - 5);
        System.out.println("Последние события:");
        lastEvents.subList(from, lastEvents.size()).forEach(s -> System.out.println("  " + s));
        Map<String, Long> kinds = journal.stream().collect(Collectors.groupingBy(
                e -> e.getClass().getSimpleName(), TreeMap::new, Collectors.counting()));
        System.out.println("Типы событий: " + kinds);
    }

    /**
     * Пары студентов с одинаковым набором сданных заданий («работали вместе?»).
     * Заложенный дефект «nested-loop-same-collection»: двойной цикл по студентам.
     */
    static void printTopPairs(List<Student> selected) {
        List<Pair<String, String>> pairs = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            for (int j = 0; j < selected.size(); j++) {
                if (i >= j) {
                    continue;
                }
                Set<String> a = bestScores(selected.get(i).id()).keySet();
                Set<String> b = bestScores(selected.get(j).id()).keySet();
                if (a.size() >= 2 && a.equals(b)) {
                    pairs.add(new Pair<>(selected.get(i).id(), selected.get(j).id()));
                }
            }
        }
        UnaryOperator<String> quote = s -> "«" + s + "»";
        Supplier<String> none = () -> "нет";
        String text = pairs.stream().limit(10).map(p -> quote.apply(p.first() + "+" + p.second()))
                .reduce((x, y) -> x + ", " + y).orElseGet(none);
        System.out.println("Одинаковый набор сданных: " + text + (pairs.size() > 10 ? " и ещё " + (pairs.size() - 10) : ""));
        BinaryOperator<Integer> add = Integer::sum;
        int totalSubmissions = selected.stream().map(s -> submissionsOf(s.id()).size()).reduce(0, add);
        System.out.println("Всего засчитанных сдач: " + totalSubmissions);
    }
}
