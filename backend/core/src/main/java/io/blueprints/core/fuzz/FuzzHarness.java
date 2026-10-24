package io.blueprints.core.fuzz;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import io.blueprints.core.model.ProjectModel;
import io.blueprints.core.model.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Фаззинг статических методов (G5): по AST находит статические методы с поддерживаемыми параметрами и генерирует
 * класс-обвязку, который вызывает каждый метод на детерминированных аргументах (сначала граничные значения, потом
 * случайные с фиксированным seed) и печатает по строке на вызов: {@code имя(аргументы) -> результат | throws Тип: msg}.
 *
 * <p>Как пользоваться (роль 4, {@code POST /verify} с {@code fuzz=true}): {@code targets = common(baseline, current)},
 * {@code harness = generate(targets, 20)}; файл обвязки добавляется к исходникам <b>обеих</b> версий, обе запускаются
 * в runner с {@code mainClass = harness.mainClass()}, stdout сравнивается. Одинаковый список целей и seed дают
 * одинаковые аргументы — расхождение вывода означает изменение поведения метода.
 *
 * <p>Методы вызываются через reflection ({@code setAccessible}), поэтому работают и private, и методы вложенных
 * static-классов. Вызов, не завершившийся за {@link #CALL_TIMEOUT_MS}, печатается как {@code -> timeout}, прогон
 * идёт дальше. Ни строки, ни числа в обвязке не зависят от времени и окружения.
 */
public final class FuzzHarness {

    public static final String MAIN_CLASS = "BlueprintsFuzzHarness";
    static final int CALL_TIMEOUT_MS = 500;

    /** Метод-цель. {@code key} — сигнатура для сопоставления версий: {@code Owner.name(int,String)}. */
    public record Target(String key, String binaryOwner, String name, List<String> paramTypes) {
    }

    /** Метод, который не берём, и почему (для отчёта: «не покрыто фаззингом»). */
    public record Skipped(String key, String reason) {
    }

    public record Scan(List<Target> targets, List<Skipped> skipped) {
    }

    public record Harness(String mainClass, SourceFile file, List<Target> targets, int casesPerTarget) {
    }

    private FuzzHarness() {
    }

    /** Статические методы проекта: поддерживаемые — в targets, остальные — в skipped с причиной. */
    public static Scan scan(ProjectModel model) {
        List<Target> targets = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (CompilationUnit cu : model.units().values()) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                String owner = binaryName(type, pkg);
                if (owner == null) {
                    continue; // локальный или анонимный класс — снаружи не вызвать
                }
                for (MethodDeclaration m : type.getMethods()) {
                    if (!m.isStatic() || m.getBody().isEmpty()) {
                        continue;
                    }
                    List<String> params = new ArrayList<>();
                    String unsupported = null;
                    for (Parameter p : m.getParameters()) {
                        String t = supported(p);
                        if (t == null) {
                            unsupported = p.getType() + (p.isVarArgs() ? "..." : "");
                            break;
                        }
                        params.add(t);
                    }
                    String key = owner + "." + m.getNameAsString() + "(" + String.join(",",
                            m.getParameters().stream().map(p -> p.getType().asString()).toList()) + ")";
                    if (m.getNameAsString().equals("main") && params.equals(List.of("String[]"))) {
                        skipped.add(new Skipped(key, "точка входа — проверяется stdin-входами"));
                    } else if (!m.getTypeParameters().isEmpty()) {
                        skipped.add(new Skipped(key, "дженерик-метод"));
                    } else if (unsupported != null) {
                        skipped.add(new Skipped(key, "тип параметра не поддержан: " + unsupported));
                    } else {
                        targets.add(new Target(key, owner, m.getNameAsString(), List.copyOf(params)));
                    }
                }
            }
        }
        return new Scan(List.copyOf(targets), List.copyOf(skipped));
    }

    /** Цели, которые есть в обеих версиях с той же сигнатурой, — в порядке базовой версии. */
    public static List<Target> common(ProjectModel baseline, ProjectModel current) {
        Map<String, Target> now = new LinkedHashMap<>();
        scan(current).targets().forEach(t -> now.put(t.key(), t));
        return scan(baseline).targets().stream().filter(t -> now.containsKey(t.key())).toList();
    }

    public static Harness generate(List<Target> targets, int casesPerTarget) {
        StringBuilder calls = new StringBuilder();
        for (Target t : targets) {
            String types = t.paramTypes().stream().map(FuzzHarness::classLiteral).collect(Collectors.joining(", "));
            String kinds = t.paramTypes().stream().map(k -> "\"" + k + "\"").collect(Collectors.joining(", "));
            calls.append("        fuzz(\"").append(t.binaryOwner()).append("\", \"").append(t.name())
                    .append("\", new Class<?>[]{").append(types).append("}, new String[]{").append(kinds)
                    .append("}, cases);\n");
        }
        String source = TEMPLATE
                .replace("$CLASS", MAIN_CLASS)
                .replace("$TIMEOUT", String.valueOf(CALL_TIMEOUT_MS))
                .replace("$CASES", String.valueOf(casesPerTarget))
                .replace("$CALLS", calls.toString());
        return new Harness(MAIN_CLASS, new SourceFile(MAIN_CLASS + ".java", source), targets, casesPerTarget);
    }

    // ---------------------------------------------------------------- типы

    private static final List<String> SIMPLE = List.of("int", "long", "double", "boolean", "char",
            "Integer", "Long", "Double", "Boolean", "Character", "String");

    /** Каноническое имя поддерживаемого типа параметра или null. */
    private static String supported(Parameter p) {
        Type t = p.getType();
        if (p.isVarArgs()) {
            String base = simple(t);
            return base != null && !base.endsWith("[]") ? base + "[]" : null;
        }
        return simple(t);
    }

    private static String simple(Type t) {
        if (t instanceof PrimitiveType pt) {
            String s = pt.asString();
            return SIMPLE.contains(s) ? s : null;
        }
        if (t instanceof ArrayType at && at.getArrayLevel() == 1) {
            String c = simple(at.getComponentType());
            return c != null && List.of("int", "long", "double", "char", "String").contains(c) ? c + "[]" : null;
        }
        if (t instanceof ClassOrInterfaceType ct) {
            String name = ct.getNameAsString();
            if (ct.getTypeArguments().isEmpty()) {
                return SIMPLE.contains(name) ? name : null;
            }
            if (ct.getTypeArguments().get().size() == 1 && List.of("List", "Collection", "Iterable").contains(name)) {
                String arg = ct.getTypeArguments().get().get(0).asString();
                return arg.equals("Integer") || arg.equals("String") ? "List<" + arg + ">" : null;
            }
        }
        return null;
    }

    private static String classLiteral(String kind) {
        return kind.startsWith("List<") ? "java.util.List.class" : kind + ".class";
    }

    /** {@code pkg.Outer$Inner}; null для локальных и анонимных классов. */
    private static String binaryName(TypeDeclaration<?> type, String pkg) {
        StringBuilder name = new StringBuilder(type.getNameAsString());
        Node n = type.getParentNode().orElse(null);
        while (n != null && !(n instanceof CompilationUnit)) {
            if (n instanceof TypeDeclaration<?> outer) {
                name.insert(0, outer.getNameAsString() + "$");
            } else {
                return null;
            }
            n = n.getParentNode().orElse(null);
        }
        return pkg + name;
    }

    /**
     * Обвязка. Аргументы: первые случаи — граничные значения по порядку, остальные — Random(seed + номер цели),
     * так что добавление цели не сдвигает аргументы других.
     */
    private static final String TEMPLATE = """
            import java.lang.reflect.*;
            import java.util.*;
            import java.util.concurrent.*;

            /** Сгенерировано Java Blueprints: фаззинг статических методов. Не редактировать. */
            public class $CLASS {
                private static final long SEED = 20261025L;
                private static final String[] WORDS = {"", "a", "abc", " ", "Привет", "a b", "0", "-1", "x,y", "ABC"};
                private static int target;

                public static void main(String[] args) throws Exception {
                    int cases = args.length > 0 ? Integer.parseInt(args[0]) : $CASES;
            $CALLS        System.out.flush();
                    System.exit(0);
                }

                static void fuzz(String owner, String name, Class<?>[] types, String[] kinds, int cases) {
                    Random random = new Random(SEED + (target++));
                    Method m;
                    try {
                        m = Class.forName(owner).getDeclaredMethod(name, types);
                        m.setAccessible(true);
                    } catch (Exception e) {
                        System.out.println(name + " -> missing: " + e.getClass().getSimpleName());
                        return;
                    }
                    for (int c = 0; c < cases; c++) {
                        Object[] values = new Object[kinds.length];
                        for (int i = 0; i < kinds.length; i++) {
                            values[i] = value(kinds[i], c, random);
                        }
                        String call = name + "(" + show(values) + ")";
                        System.out.println(call + " -> " + invoke(m, values));
                    }
                }

                static String invoke(Method m, Object[] values) {
                    ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    });
                    try {
                        Future<Object> f = ex.submit(() -> m.invoke(null, values));
                        Object result = f.get($TIMEOUT, TimeUnit.MILLISECONDS);
                        return m.getReturnType() == void.class ? "ok" : show(new Object[]{result});
                    } catch (TimeoutException e) {
                        return "timeout";
                    } catch (ExecutionException e) {
                        Throwable t = e.getCause() instanceof InvocationTargetException ite ? ite.getCause() : e.getCause();
                        return "throws " + t.getClass().getName() + ": " + t.getMessage();
                    } catch (InterruptedException e) {
                        return "interrupted";
                    } finally {
                        ex.shutdownNow();
                    }
                }

                static Object value(String kind, int c, Random r) {
                    switch (kind) {
                        case "int": case "Integer": return pick(c, new Integer[]{0, 1, -1, 2, 10, Integer.MAX_VALUE, Integer.MIN_VALUE}, () -> r.nextInt(201) - 100);
                        case "long": case "Long": return pick(c, new Long[]{0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE}, () -> (long) (r.nextInt(2001) - 1000));
                        case "double": case "Double": return pick(c, new Double[]{0.0, 1.0, -1.0, 0.5, Double.NaN}, () -> Math.round((r.nextDouble() * 200 - 100) * 100) / 100.0);
                        case "boolean": case "Boolean": return c % 2 == 0;
                        case "char": case "Character": return pick(c, new Character[]{'a', 'Z', '0', ' ', 'ж'}, () -> (char) ('a' + r.nextInt(26)));
                        case "String": return pick(c, WORDS, () -> word(r));
                        case "int[]": { int n = size(c, r); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = r.nextInt(41) - 20; return a; }
                        case "long[]": { int n = size(c, r); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = r.nextInt(41) - 20; return a; }
                        case "double[]": { int n = size(c, r); double[] a = new double[n]; for (int i = 0; i < n; i++) a[i] = r.nextInt(41) - 20; return a; }
                        case "char[]": return word(r).toCharArray();
                        case "String[]": { int n = size(c, r); String[] a = new String[n]; for (int i = 0; i < n; i++) a[i] = word(r); return a; }
                        case "List<Integer>": { int n = size(c, r); List<Integer> a = new ArrayList<>(); for (int i = 0; i < n; i++) a.add(r.nextInt(41) - 20); return a; }
                        case "List<String>": { int n = size(c, r); List<String> a = new ArrayList<>(); for (int i = 0; i < n; i++) a.add(word(r)); return a; }
                        default: throw new IllegalArgumentException(kind);
                    }
                }

                static <T> T pick(int c, T[] edges, java.util.function.Supplier<T> random) {
                    return c < edges.length ? edges[c] : random.get();
                }

                static int size(int c, Random r) {
                    return c == 0 ? 0 : c == 1 ? 1 : r.nextInt(8);
                }

                static String word(Random r) {
                    return r.nextInt(4) == 0 ? WORDS[r.nextInt(WORDS.length)] : Integer.toString(r.nextInt(50), 36);
                }

                static String show(Object[] values) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < values.length; i++) {
                        if (i > 0) sb.append(", ");
                        Object v = values[i];
                        if (v instanceof String s) sb.append('"').append(s).append('"');
                        else if (v instanceof Character ch) sb.append('\\'').append(ch).append('\\'');
                        else if (v != null && v.getClass().isArray()) sb.append(Arrays.deepToString(new Object[]{v}).replaceAll("^\\\\[|\\\\]$", ""));
                        else sb.append(v);
                    }
                    return sb.toString();
                }
            }
            """;
}
