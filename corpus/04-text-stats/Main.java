import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Монолит «Анализ текстов»: читает текст из stdin (русский и английский), считает статистику по словам, предложениям
 * и символам, строит частотный словарь, n-граммы, ищет палиндромы и повторы, проверяет «читабельность»,
 * печатает отчёт. Первая строка может задавать режим: {@code #mode full|short|words}.
 *
 * Акцент: String / StringBuilder / Character, char-арифметика, StandardCharsets, Scanner, printf и Formatter,
 * switch по строкам, метки break/continue, массивы-счётчики, TreeMap/HashMap/ArrayList, исключения.
 */
public class Main {

    static final String VOWELS_RU = "аеёиоуыэюя";
    static final String VOWELS_EN = "aeiouy";
    static final int TOP = 10;
    static final char[] SENTENCE_END = {'.', '!', '?', '…'};

    /** Ошибка формата управляющей строки — проверяемое исключение. */
    static class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    static class TextTooLongException extends RuntimeException {
        TextTooLongException(int length) {
            super("текст слишком длинный: " + length + " символов");
        }
    }

    static final class WordInfo implements Comparable<WordInfo> {
        final String word;
        int count;
        int firstLine;
        int firstPosition;

        WordInfo(String word, int line, int position) {
            this.word = word;
            this.firstLine = line;
            this.firstPosition = position;
        }

        @Override
        public int compareTo(WordInfo o) {
            if (count != o.count) {
                return o.count - count;
            }
            return word.compareTo(o.word);
        }

        @Override
        public String toString() {
            return word + "×" + count;
        }
    }

    // ---------------------------------------------------------------- состояние

    static String mode = "full";
    static final List<String> lines = new ArrayList<>();
    static final Map<String, WordInfo> words = new HashMap<>();
    static final List<String> wordSequence = new ArrayList<>();
    static final int[] letterCounts = new int[33 + 26];
    static int cyrillic;
    static int latin;
    static int digits;
    static int spaces;
    static int punctuation;
    static int other;
    static int sentences;

    public static void main(String[] args) {
        Scanner in = new Scanner(System.in, StandardCharsets.UTF_8);
        int totalChars = 0;
        boolean first = true;
        while (in.hasNextLine()) {
            String line = in.nextLine();
            if (first) {
                first = false;
                if (line.startsWith("#")) {
                    try {
                        mode = parseMode(line);
                    } catch (FormatException e) {
                        System.err.println("Предупреждение: " + e.getMessage() + " — используется режим full");
                    }
                    continue;
                }
            }
            totalChars += line.length();
            if (totalChars > 2_000_000) {
                throw new TextTooLongException(totalChars);
            }
            lines.add(line);
        }
        if (lines.isEmpty() || lines.stream().allMatch(String::isBlank)) {
            System.out.println("Пустой текст — анализировать нечего.");
            return;
        }
        analyzeCharacters();
        analyzeWords();
        switch (mode) {
            case "short":
                printShort();
                break;
            case "words":
                printWords();
                break;
            default:
                printShort();
                printWords();
                printFull();
        }
    }

    static String parseMode(String header) throws FormatException {
        String[] p = header.substring(1).trim().split("\\s+");
        if (p.length != 2 || !p[0].equals("mode")) {
            throw new FormatException("ожидается «#mode <full|short|words>», получено «" + header + "»");
        }
        String m = p[1].toLowerCase(Locale.ROOT);
        if (!m.equals("full") && !m.equals("short") && !m.equals("words")) {
            throw new FormatException("неизвестный режим " + m);
        }
        return m;
    }

    // ---------------------------------------------------------------- символы

    static void analyzeCharacters() {
        for (String line : lines) {
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c >= 'а' && c <= 'я' || c >= 'А' && c <= 'Я' || c == 'ё' || c == 'Ё') {
                    cyrillic++;
                    char lower = Character.toLowerCase(c);
                    int idx = lower == 'ё' ? 6 : (lower > 'е' ? lower - 'а' + 1 : lower - 'а');
                    letterCounts[idx]++;
                } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                    latin++;
                    letterCounts[33 + (Character.toLowerCase(c) - 'a')]++;
                } else if (Character.isDigit(c)) {
                    digits++;
                } else if (Character.isWhitespace(c)) {
                    spaces++;
                } else if (isPunctuation(c)) {
                    punctuation++;
                    if (isSentenceEnd(c) && (i + 1 == line.length() || !isSentenceEnd(line.charAt(i + 1)))) {
                        sentences++;
                    }
                } else {
                    other++;
                }
            }
        }
    }

    static boolean isPunctuation(char c) {
        return ".,;:!?…-—–«»\"'()[]".indexOf(c) >= 0;
    }

    static boolean isSentenceEnd(char c) {
        for (char e : SENTENCE_END) {
            if (e == c) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- слова

    static void analyzeWords() {
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i <= line.length(); i++) {
                char c = i < line.length() ? line.charAt(i) : ' ';
                if (Character.isLetterOrDigit(c) || (c == '-' && current.length() > 0)) {
                    current.append(Character.toLowerCase(c));
                } else if (current.length() > 0) {
                    String w = trimHyphens(current.toString());
                    if (!w.isEmpty()) {
                        addWord(w, lineNo, i - current.length() + 1);
                    }
                    current.setLength(0);
                }
            }
        }
    }

    static String trimHyphens(String w) {
        int start = 0;
        int end = w.length();
        while (start < end && w.charAt(start) == '-') {
            start++;
        }
        while (end > start && w.charAt(end - 1) == '-') {
            end--;
        }
        return w.substring(start, end);
    }

    static void addWord(String w, int line, int position) {
        WordInfo info = words.get(w);
        if (info == null) {
            info = new WordInfo(w, line, position);
            words.put(w, info);
        }
        info.count++;
        wordSequence.add(w);
    }

    // ---------------------------------------------------------------- отчёты

    static void printShort() {
        int totalWords = wordSequence.size();
        System.out.println("== Кратко ==");
        System.out.printf("Строк: %d, слов: %d, уникальных: %d, предложений: %d%n",
                lines.size(), totalWords, words.size(), sentences);
        System.out.printf("Символы: кириллица %d, латиница %d, цифры %d, пробелы %d, пунктуация %d, прочее %d%n",
                cyrillic, latin, digits, spaces, punctuation, other);
        int bytes = 0;
        for (String l : lines) {
            bytes += l.getBytes(StandardCharsets.UTF_8).length;
        }
        System.out.printf("Размер в UTF-8: %d байт (%.2f байта на символ)%n", bytes,
                (double) bytes / Math.max(1, cyrillic + latin + digits + spaces + punctuation + other));
        double avg = totalWords == 0 ? 0 : (double) totalLetters() / totalWords;
        System.out.printf("Средняя длина слова: %.2f%n", avg);
        System.out.println("Язык: " + (cyrillic >= latin ? "русский" : "английский"));
    }

    static int totalLetters() {
        int sum = 0;
        for (String w : wordSequence) {
            sum += w.length();
        }
        return sum;
    }

    static void printWords() {
        System.out.println("== Частотный словарь (топ " + TOP + ") ==");
        List<WordInfo> sorted = new ArrayList<>(words.values());
        Collections.sort(sorted);
        int rank = 0;
        for (WordInfo wi : sorted) {
            if (++rank > TOP) {
                break;
            }
            System.out.printf("%2d. %-15s %3d  (впервые: строка %d, позиция %d)%n",
                    rank, wi.word, wi.count, wi.firstLine, wi.firstPosition);
        }
        System.out.println("Длинные слова: " + longestWords(5));
    }

    static List<String> longestWords(int n) {
        List<String> all = new ArrayList<>(words.keySet());
        all.sort((a, b) -> b.length() != a.length() ? b.length() - a.length() : a.compareTo(b));
        return all.subList(0, Math.min(n, all.size()));
    }

    static void printFull() {
        System.out.println("== Подробно ==");
        printLetters();
        printBigrams();
        printPalindromes();
        printRepeats();
        printReadability();
        printLongestSentence();
        printCaesarSample();
        printAnagrams();
    }

    static void printLetters() {
        StringBuilder sb = new StringBuilder("Буквы:");
        int shown = 0;
        int[] order = topIndexes(letterCounts, 8);
        for (int idx : order) {
            if (letterCounts[idx] == 0) {
                continue;
            }
            sb.append(' ').append(letterAt(idx)).append('=').append(letterCounts[idx]);
            shown++;
        }
        System.out.println(shown == 0 ? "Буквы: нет" : sb.toString());
        int vowels = 0;
        int consonants = 0;
        for (int i = 0; i < letterCounts.length; i++) {
            char ch = letterAt(i);
            if (VOWELS_RU.indexOf(ch) >= 0 || VOWELS_EN.indexOf(ch) >= 0) {
                vowels += letterCounts[i];
            } else {
                consonants += letterCounts[i];
            }
        }
        System.out.printf("Гласных %d, согласных %d%n", vowels, consonants);
    }

    static char letterAt(int idx) {
        if (idx >= 33) {
            return (char) ('a' + idx - 33);
        }
        if (idx == 6) {
            return 'ё';
        }
        return (char) ('а' + (idx > 6 ? idx - 1 : idx));
    }

    /** Индексы n наибольших значений — выбором, без сортировки (O(n·k)). */
    static int[] topIndexes(int[] values, int n) {
        int[] result = new int[Math.min(n, values.length)];
        boolean[] used = new boolean[values.length];
        for (int k = 0; k < result.length; k++) {
            int best = -1;
            for (int i = 0; i < values.length; i++) {
                if (!used[i] && (best < 0 || values[i] > values[best])) {
                    best = i;
                }
            }
            used[best] = true;
            result[k] = best;
        }
        return result;
    }

    static void printBigrams() {
        Map<String, Integer> bigrams = new TreeMap<>();
        for (int i = 0; i + 1 < wordSequence.size(); i++) {
            String key = wordSequence.get(i) + " " + wordSequence.get(i + 1);
            bigrams.merge(key, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(bigrams.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        // заложенный дефект «string-concat-in-loop»
        String out = "";
        int shown = 0;
        for (Map.Entry<String, Integer> e : list) {
            if (e.getValue() < 2 || shown == 5) {
                break;
            }
            out += "«" + e.getKey() + "»=" + e.getValue() + " ";
            shown++;
        }
        System.out.println("Повторяющиеся пары слов: " + (out.isEmpty() ? "нет" : out.trim()));
    }

    static void printPalindromes() {
        Set<String> found = new TreeSet<>();
        for (String w : words.keySet()) {
            if (w.length() >= 3 && isPalindrome(w)) {
                found.add(w);
            }
        }
        System.out.println("Палиндромы: " + (found.isEmpty() ? "нет" : String.join(", ", found)));
    }

    static boolean isPalindrome(String s) {
        return new StringBuilder(s).reverse().toString().equals(s);
    }

    /**
     * Слова, повторённые подряд или через одно слово («очень очень», «да нет да»).
     * Заложенный дефект «collection-lookup-in-loop»: List.contains в цикле.
     */
    static void printRepeats() {
        List<String> repeats = new ArrayList<>();
        outer:
        for (int i = 1; i < wordSequence.size(); i++) {
            String w = wordSequence.get(i);
            for (int back = 1; back <= 2 && i - back >= 0; back++) {
                if (wordSequence.get(i - back).equals(w)) {
                    if (!repeats.contains(w)) {
                        repeats.add(w);
                    }
                    continue outer;
                }
            }
        }
        System.out.println("Повторы рядом: " + (repeats.isEmpty() ? "нет" : repeats));
    }

    /** Индекс удобочитаемости Флеша (адаптация Оборневой для русского). Заложенный дефект «magic-number». */
    static void printReadability() {
        int totalWords = wordSequence.size();
        if (totalWords == 0 || sentences == 0) {
            System.out.println("Читабельность: недостаточно данных");
            return;
        }
        int syllables = 0;
        for (String w : wordSequence) {
            syllables += syllables(w);
        }
        double asl = (double) totalWords / sentences;
        double asw = (double) syllables / totalWords;
        double fre = cyrillic >= latin
                ? 206.835 - 1.3 * asl - 60.1 * asw
                : 206.835 - 1.015 * asl - 84.6 * asw;
        String level;
        if (fre > 80) {
            level = "очень легко";
        } else if (fre > 60) {
            level = "легко";
        } else if (fre > 40) {
            level = "средне";
        } else if (fre > 20) {
            level = "сложно";
        } else {
            level = "очень сложно";
        }
        System.out.printf("Читабельность: %.1f (%s), слов в предложении %.1f, слогов в слове %.2f%n", fre, level, asl, asw);
    }

    static int syllables(String w) {
        int n = 0;
        for (char c : w.toCharArray()) {
            if (VOWELS_RU.indexOf(c) >= 0 || VOWELS_EN.indexOf(c) >= 0) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    static void printLongestSentence() {
        String text = String.join(" ", lines);
        int bestStart = 0;
        int bestLen = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isSentenceEnd(text.charAt(i)) || i == text.length() - 1) {
                int len = i - start + 1;
                if (len > bestLen) {
                    bestLen = len;
                    bestStart = start;
                }
                start = i + 1;
            }
        }
        String s = text.substring(bestStart, bestStart + bestLen).trim();
        if (s.length() > 80) {
            s = s.substring(0, 77) + "...";
        }
        System.out.println("Самое длинное предложение (" + bestLen + " симв.): " + s);
    }

    /** Шифр Цезаря для первой строки — char-арифметика по двум алфавитам. */
    static void printCaesarSample() {
        String src = lines.get(0);
        if (src.length() > 40) {
            src = src.substring(0, 40);
        }
        String enc = caesar(src, 3);
        String dec = caesar(enc, -3);
        System.out.println("Цезарь(+3): " + enc + (dec.equals(src) ? " — обратимо" : " — ОШИБКА"));
    }

    static String caesar(String s, int shift) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= 'а' && c <= 'я') {
                sb.append((char) ('а' + Math.floorMod(c - 'а' + shift, 32)));
            } else if (c >= 'А' && c <= 'Я') {
                sb.append((char) ('А' + Math.floorMod(c - 'А' + shift, 32)));
            } else if (c >= 'a' && c <= 'z') {
                sb.append((char) ('a' + Math.floorMod(c - 'a' + shift, 26)));
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) ('A' + Math.floorMod(c - 'A' + shift, 26)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Анаграммы среди уникальных слов. Заложенный дефект «nested-loop-same-collection»: O(n²) по словам. */
    static void printAnagrams() {
        List<String> unique = new ArrayList<>(words.keySet());
        Collections.sort(unique);
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < unique.size(); i++) {
            for (int j = 0; j < unique.size(); j++) {
                String a = unique.get(i);
                String b = unique.get(j);
                if (i < j && a.length() > 2 && a.length() == b.length() && sortedChars(a).equals(sortedChars(b))) {
                    pairs.add(a + "/" + b);
                }
            }
        }
        System.out.println("Анаграммы: " + (pairs.isEmpty() ? "нет" : pairs.subList(0, Math.min(10, pairs.size()))));
    }

    static String sortedChars(String s) {
        char[] arr = s.toCharArray();
        Arrays.sort(arr);
        return new String(arr);
    }
}
