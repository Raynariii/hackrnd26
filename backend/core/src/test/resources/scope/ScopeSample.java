import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** Конструкции обязательного scope (разделы 1–4) и бонуса — для проверки разбора, печати и графов. */
public class ScopeSample {

    interface Shape {
        double area();

        default String describe() {
            return getClass().getSimpleName() + " " + String.format("%.2f", area());
        }
    }

    sealed interface Token permits Num, Op {}

    record Num(int value) implements Token {}

    record Op(char symbol) implements Token {
        Op {
            if ("+-*/".indexOf(symbol) < 0) {
                throw new IllegalArgumentException("bad op " + symbol);
            }
        }
    }

    enum Level {
        LOW(1), MID(5), HIGH(10);

        private final int weight;

        Level(int weight) {
            this.weight = weight;
        }

        int weight() {
            return weight;
        }
    }

    static abstract class Base implements Shape {
        protected final String name;

        Base(String name) {
            this.name = name;
        }
    }

    static final class Circle extends Base {
        private final double r;

        Circle(double r) {
            super("circle");
            this.r = r;
        }

        @Override
        public double area() {
            return Math.PI * r * r;
        }
    }

    static class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }
    }

    static class Box<T extends Comparable<T>> {
        private final List<T> items = new ArrayList<>();

        @SafeVarargs
        final void addAll(T... values) {
            items.addAll(Arrays.asList(values));
        }

        Optional<T> max() {
            return items.stream().max(Comparator.naturalOrder());
        }
    }

    private static int counter;
    private static final int[] PRIMES = {2, 3, 5, 7, 11};
    private final Map<String, Integer> totals = new TreeMap<>();

    static int classify(Object o) {
        if (o instanceof Integer i && i > 0) {
            return 1;
        } else if (o instanceof String s) {
            return s.isEmpty() ? 0 : 2;
        }
        return -1;
    }

    static String day(int d) {
        return switch (d) {
            case 1, 7 -> "weekend";
            case 2, 3, 4, 5, 6 -> {
                String w = "work";
                yield w + "day";
            }
            default -> throw new IllegalStateException("day " + d);
        };
    }

    static long legacySwitch(char c) {
        long result = 0;
        switch (c) {
            case 'a':
                result += 1;
            case 'b':
                result += 2;
                break;
            default:
                result = -1;
        }
        return result;
    }

    static int loops(int[][] grid) {
        int found = 0;
        outer:
        for (int i = 0; i < grid.length; i++) {
            for (int j = 0; j < grid[i].length; j++) {
                if (grid[i][j] < 0) {
                    continue outer;
                }
                if (grid[i][j] == 42) {
                    break outer;
                }
                found += grid[i][j] % 2 == 0 ? 1 : 0;
            }
        }
        int k = 0;
        do {
            k++;
        } while (k < 3);
        while (true) {
            if (++k > 10) {
                break;
            }
        }
        for (;;) {
            if (k-- < 5) {
                break;
            }
        }
        return found + k;
    }

    static void validate(String s) throws ValidationException {
        if (s == null || s.isBlank()) {
            throw new ValidationException("empty");
        }
    }

    void tryAll(String text) {
        try (Scanner in = new Scanner(text)) {
            while (in.hasNextLine()) {
                String line = in.nextLine();
                validate(line);
                totals.merge(line, 1, Integer::sum);
            }
        } catch (ValidationException | IllegalStateException e) {
            System.err.println("ошибка: " + e.getMessage());
        } finally {
            counter++;
        }
    }

    static String streams(List<String> words) {
        Map<Integer, List<String>> byLength = words.stream()
                .filter(w -> !w.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.groupingBy(String::length, TreeMap::new, Collectors.toList()));
        Function<Integer, Integer> square = x -> x * x;
        BiFunction<Integer, Integer, Integer> add = Integer::sum;
        Supplier<List<Integer>> empty = ArrayList::new;
        Predicate<String> longWord = w -> w.length() > 5;
        int total = IntStream.rangeClosed(1, 5).map(square::apply).sum();
        List<Integer> sink = empty.get();
        sink.add(add.apply(total, 1));
        Runnable r = new Runnable() {
            @Override
            public void run() {
                sink.add(counter);
            }
        };
        r.run();
        return byLength + " " + words.stream().anyMatch(longWord) + " " + sink;
    }

    static String collections() {
        List<Integer> list = new LinkedList<>(List.of(5, 3, 8));
        Set<String> set = new HashSet<>(Set.of("a", "b"));
        Map<String, Integer> map = new HashMap<>(Map.of("x", 1));
        Deque<Integer> stack = new ArrayDeque<>();
        Collections.sort(list);
        Iterator<Integer> it = list.iterator();
        while (it.hasNext()) {
            int v = it.next();
            if (v > 6) {
                it.remove();
            } else {
                stack.push(v);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        char[] chars = sb.toString().toCharArray();
        Arrays.sort(chars);
        long big = (long) Integer.MAX_VALUE + 1;
        byte b = (byte) big;
        String text = """
                многострочный
                текст""";
        return list + " " + set.size() + " " + stack.peek() + " " + new String(chars) + " " + b + " "
                + Level.valueOf("MID").weight() + " " + text.lines().count() + " " + PRIMES.length;
    }

    public static void main(String[] args) {
        byte[] bytes = "привет".getBytes(StandardCharsets.UTF_8);
        System.out.printf("%d bytes%n", bytes.length);
        Box<Integer> box = new Box<>();
        box.addAll(3, 9, 4);
        System.out.println(box.max().orElse(-1));
        System.out.println(new Circle(1).describe());
        System.out.println(classify(5) + classify("x") + day(3) + legacySwitch('a'));
        System.out.println(loops(new int[][]{{1, 2}, {-1, 42}}));
        var sample = new ScopeSample();
        sample.tryAll("a\nb\na");
        System.out.println(sample.totals + streams(List.of("Alpha", "be", "")) + collections());
        Token t = new Op('+');
        String kind = t instanceof Num n ? "num " + n.value() : "op";
        synchronized (ScopeSample.class) {
            counter += kind.length();
        }
        assert counter > 0 : "counter";
    }
}
