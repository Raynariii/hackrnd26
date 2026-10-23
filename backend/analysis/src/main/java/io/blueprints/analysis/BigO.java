package io.blueprints.analysis;

/**
 * Асимптотическая сложность вида O(n^k · log n) — то, что показываем пользователю
 * в {@code Finding.complexity} и {@code MethodMetrics.complexity}.
 *
 * <p>Модель сознательно простая: сложность считается по числу вложенных циклов
 * (каждый цикл даёт множитель n) и по таблице стоимости операций коллекций
 * ({@link CostModel}). Так оценка остаётся объяснимой для жюри и не зависит
 * от запуска кода: O(n²) в {@code processOrders} выводится из AST, а не из замеров.
 *
 * <p>Экземпляры неизменяемы; операции {@link #times(BigO)} и {@link #max(BigO)}
 * замкнуты, поэтому сложность можно сворачивать по дереву обхода.
 */
public final class BigO implements Comparable<BigO> {

    private static final BigO CONSTANT = new BigO(0, false);

    private final int power;
    private final boolean logarithmic;

    private BigO(int power, boolean logarithmic) {
        this.power = power;
        this.logarithmic = logarithmic;
    }

    /** O(1). */
    public static BigO constant() {
        return CONSTANT;
    }

    /** O(n^power); для power ≤ 0 — O(1). */
    public static BigO of(int power) {
        return power <= 0 ? CONSTANT : new BigO(power, false);
    }

    /** O(log n). */
    public static BigO log() {
        return new BigO(0, true);
    }

    /** O(n log n). */
    public static BigO nLogN() {
        return new BigO(1, true);
    }

    /** Произведение сложностей: вложенный цикл вокруг операции. */
    public BigO times(BigO other) {
        return new BigO(power + other.power, logarithmic || other.logarithmic);
    }

    /** Максимум из двух сложностей: самый дорогой путь в теле. */
    public BigO max(BigO other) {
        return compareTo(other) >= 0 ? this : other;
    }

    public int power() {
        return power;
    }

    public boolean isLogarithmic() {
        return logarithmic;
    }

    @Override
    public int compareTo(BigO other) {
        int byPower = Integer.compare(power, other.power);
        return byPower != 0 ? byPower : Boolean.compare(logarithmic, other.logarithmic);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BigO o && o.power == power && o.logarithmic == logarithmic;
    }

    @Override
    public int hashCode() {
        return 31 * power + (logarithmic ? 1 : 0);
    }

    @Override
    public String toString() {
        if (power == 0) {
            return logarithmic ? "O(log n)" : "O(1)";
        }
        String base = switch (power) {
            case 1 -> "n";
            case 2 -> "n²";
            case 3 -> "n³";
            case 4 -> "n⁴";
            case 5 -> "n⁵";
            case 6 -> "n⁶";
            case 7 -> "n⁷";
            case 8 -> "n⁸";
            case 9 -> "n⁹";
            default -> "n^" + power;
        };
        return logarithmic ? "O(" + base + " log n)" : "O(" + base + ")";
    }
}
