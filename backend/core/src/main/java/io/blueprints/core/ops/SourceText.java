package io.blueprints.core.ops;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Текст одного файла и правки в границах AST-узлов. Позиции JavaParser (строка, столбец с 1, таб = 1 столбец)
 * переводятся в смещения; правки применяются с конца файла, чтобы смещения не съезжали.
 */
final class SourceText {

    record Edit(int start, int end, String text) {
    }

    private final String text;
    private final List<Integer> lineStarts = new ArrayList<>();
    private final String newline;
    private final List<Edit> edits = new ArrayList<>();

    SourceText(String text) {
        this.text = text;
        lineStarts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
        this.newline = text.contains("\r\n") ? "\r\n" : "\n";
    }

    String text() {
        return text;
    }

    String newline() {
        return newline;
    }

    int offset(Position p) {
        return lineStarts.get(p.line - 1) + p.column - 1;
    }

    /** Начало узла. */
    int begin(Node node) {
        return offset(node.getBegin().orElseThrow(() -> new UnsupportedGraphOpException("У узла нет позиции в коде")));
    }

    /** Конец узла (не включая). */
    int end(Node node) {
        return offset(node.getEnd().orElseThrow(() -> new UnsupportedGraphOpException("У узла нет позиции в коде"))) + 1;
    }

    String slice(int start, int end) {
        return text.substring(start, end);
    }

    String source(Node node) {
        return slice(begin(node), end(node));
    }

    int lineStart(int offset) {
        int i = offset;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    /** Смещение символа перевода строки (или конца файла) на строке offset. */
    int lineEnd(int offset) {
        int i = offset;
        while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
            i++;
        }
        return i;
    }

    /** Смещение начала следующей строки (после перевода строки). */
    int nextLine(int offset) {
        int i = lineEnd(offset);
        if (i < text.length() && text.charAt(i) == '\r') {
            i++;
        }
        if (i < text.length() && text.charAt(i) == '\n') {
            i++;
        }
        return i;
    }

    /** Отступ строки, на которой стоит offset. */
    String indentAt(int offset) {
        int start = lineStart(offset);
        int i = start;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return text.substring(start, i);
    }

    /** Отступ на уровень глубже: таб, если файл с табами, иначе четыре пробела. */
    static String deeper(String indent) {
        return indent + (indent.contains("\t") ? "\t" : "    ");
    }

    boolean blankBefore(int offset) {
        return text.substring(lineStart(offset), offset).isBlank();
    }

    /** До конца строки только пробелы или однострочный комментарий. */
    boolean blankOrCommentAfter(int offset) {
        String rest = text.substring(offset, lineEnd(offset)).strip();
        return rest.isEmpty() || rest.startsWith("//");
    }

    /** Если после offset (через пробелы) стоит {@code ;} — смещение за ней, иначе offset. */
    int skipSemicolon(int offset) {
        int i = offset;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i < text.length() && text.charAt(i) == ';' ? i + 1 : offset;
    }

    /** Первое вхождение символа начиная с offset. */
    int find(char c, int offset) {
        int i = text.indexOf(c, offset);
        if (i < 0) {
            throw new UnsupportedGraphOpException("Не удалось найти '" + c + "' в коде");
        }
        return i;
    }

    /**
     * Код фрагмента с отступом места вставки: общий отступ фрагмента снимается, строки со второй
     * получают {@code indent}. Первая строка — без отступа: он уже стоит в месте вставки.
     */
    String reindent(String code, String indent) {
        List<String> lines = new ArrayList<>(code.stripTrailing().lines().toList());
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        int common = lines.stream().filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).isBlank() ? "" : lines.get(i).substring(common).stripTrailing();
            if (i > 0) {
                out.append(newline).append(line.isEmpty() ? "" : indent);
            }
            out.append(line);
        }
        return out.toString();
    }

    void replace(int start, int end, String replacement) {
        edits.add(new Edit(start, end, replacement));
    }

    void insert(int at, String insertion) {
        edits.add(new Edit(at, at, insertion));
    }

    /**
     * Применить правки. Правка внутри другой (удалили и цикл, и оператор в нём) отбрасывается; частичное
     * пересечение — ошибка.
     */
    String apply() {
        List<Edit> sorted = new ArrayList<>(edits);
        // При одинаковом начале вставка идёт раньше замены: в тексте она окажется перед заменённым куском.
        sorted.sort(Comparator.comparingInt(Edit::start)
                .thenComparing(e -> e.start() != e.end())
                .thenComparing(Comparator.comparingInt(Edit::end).reversed()));
        List<Edit> kept = new ArrayList<>();
        for (Edit e : sorted) {
            Edit last = kept.isEmpty() ? null : kept.get(kept.size() - 1);
            if (last != null && e.start() < last.end()) {
                if (e.end() <= last.end() && e.start() != e.end()) {
                    continue;
                }
                throw new UnsupportedGraphOpException("Правки пересекаются — выберите непересекающиеся узлы");
            }
            kept.add(e);
        }
        StringBuilder out = new StringBuilder(text);
        for (int i = kept.size() - 1; i >= 0; i--) {
            Edit e = kept.get(i);
            out.replace(e.start(), e.end(), e.text());
        }
        return out.toString();
    }
}
