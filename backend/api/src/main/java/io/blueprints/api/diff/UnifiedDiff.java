package io.blueprints.api.diff;

import io.blueprints.core.model.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unified diff между двумя версиями проекта (для ChangeResult.diff). Без внешних зависимостей: LCS по строкам. */
public final class UnifiedDiff {

    private static final int CONTEXT = 3;
    /** Выше этого произведения длин LCS-таблица слишком большая — показываем файл заменённым целиком. */
    private static final long MAX_CELLS = 25_000_000L;

    private UnifiedDiff() {
    }

    public static String of(List<SourceFile> before, List<SourceFile> after) {
        Map<String, String> old = toMap(before);
        Map<String, String> neu = toMap(after);
        List<String> paths = new ArrayList<>(old.keySet());
        neu.keySet().stream().filter(p -> !old.containsKey(p)).forEach(paths::add);
        StringBuilder out = new StringBuilder();
        for (String path : paths) {
            String a = old.get(path);
            String b = neu.get(path);
            if (a != null && a.equals(b)) {
                continue;
            }
            out.append(file(path, a, b));
        }
        return out.toString();
    }

    private static Map<String, String> toMap(List<SourceFile> files) {
        Map<String, String> map = new LinkedHashMap<>();
        files.forEach(f -> map.put(f.path(), f.content()));
        return map;
    }

    static String file(String path, String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<Op> ops = diff(a, b);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(before == null ? "/dev/null" : "a/" + path).append('\n');
        out.append("+++ ").append(after == null ? "/dev/null" : "b/" + path).append('\n');

        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind == ' ') {
                i++;
                continue;
            }
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            // расширяем ханк, пока изменения разделены не более чем 2*CONTEXT строками контекста
            int j = i;
            while (j < ops.size()) {
                if (ops.get(j).kind != ' ') {
                    end = j;
                    j++;
                    continue;
                }
                int k = j;
                while (k < ops.size() && ops.get(k).kind == ' ') {
                    k++;
                }
                if (k < ops.size() && k - j <= 2 * CONTEXT) {
                    j = k;
                } else {
                    break;
                }
            }
            int stop = Math.min(ops.size(), end + 1 + CONTEXT);
            int aStart = ops.get(start).aLine;
            int bStart = ops.get(start).bLine;
            int aCount = 0;
            int bCount = 0;
            StringBuilder body = new StringBuilder();
            for (int k = start; k < stop; k++) {
                Op op = ops.get(k);
                body.append(op.kind).append(op.text).append('\n');
                if (op.kind != '+') {
                    aCount++;
                }
                if (op.kind != '-') {
                    bCount++;
                }
            }
            out.append("@@ -").append(range(aStart, aCount)).append(" +").append(range(bStart, bCount)).append(" @@\n");
            out.append(body);
            i = stop;
        }
        return out.toString();
    }

    private static String range(int startIndex, int count) {
        int line = count == 0 ? startIndex : startIndex + 1;
        return count == 1 ? String.valueOf(line) : line + "," + count;
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n");
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return List.of(normalized.split("\n", -1));
    }

    private record Op(char kind, String text, int aLine, int bLine) {
    }

    private static List<Op> diff(List<String> a, List<String> b) {
        // общий префикс и суффикс снимаем сразу — обычно правка локальная, и таблица LCS остаётся маленькой
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(' ', a.get(i), i, i));
        }
        List<String> am = a.subList(prefix, a.size() - suffix);
        List<String> bm = b.subList(prefix, b.size() - suffix);
        int n = am.size();
        int m = bm.size();
        if ((long) n * m > MAX_CELLS) {
            for (int i = 0; i < n; i++) {
                ops.add(new Op('-', am.get(i), prefix + i, prefix));
            }
            for (int j = 0; j < m; j++) {
                ops.add(new Op('+', bm.get(j), prefix + n, prefix + j));
            }
        } else {
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    lcs[i][j] = am.get(i).equals(bm.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && am.get(i).equals(bm.get(j))) {
                    ops.add(new Op(' ', am.get(i), prefix + i, prefix + j));
                    i++;
                    j++;
                } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    ops.add(new Op('-', am.get(i), prefix + i, prefix + j));
                    i++;
                } else {
                    ops.add(new Op('+', bm.get(j), prefix + i, prefix + j));
                    j++;
                }
            }
        }
        for (int k = suffix; k > 0; k--) {
            int ai = a.size() - k;
            int bi = b.size() - k;
            ops.add(new Op(' ', a.get(ai), ai, bi));
        }
        return ops;
    }
}
