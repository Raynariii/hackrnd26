import java.util.*;

/**
 * Монолит «Городская библиотека»: каталог книг, читатели, выдача и возврат, очередь бронирования, штрафы, отчёты.
 * Акцент — коллекции java.util (List/Set/Map, LinkedList, TreeSet, TreeMap, Iterator, Collections, Arrays,
 * Comparator, Optional, List.of / Set.of / Map.of) и исключения (проверяемые, непроверяемые, свои иерархии).
 *
 * Команды (по одной в строке, день — целое число):
 *   BOOK <isbn> <год> <автор> | <название>
 *   COPY <isbn> <количество>
 *   READER <id> <тип: STUDENT|ADULT|SENIOR> <имя>
 *   TAKE <день> <readerId> <isbn>
 *   RETURN <день> <readerId> <isbn>
 *   RESERVE <readerId> <isbn>
 *   SEARCH <слово>
 *   TAGS <isbn> <тег,тег,...>
 *   STATS
 */
public class Main {

    // ---------------------------------------------------------------- константы и справочники

    static final List<String> READER_TYPES = List.of("STUDENT", "ADULT", "SENIOR");
    static final Map<String, Integer> LOAN_DAYS = Map.of("STUDENT", 21, "ADULT", 14, "SENIOR", 28);
    static final Map<String, Integer> MAX_BOOKS = Map.of("STUDENT", 5, "ADULT", 3, "SENIOR", 4);
    static final Set<String> STOP_WORDS = Set.of("и", "в", "на", "the", "of", "a");

    // ---------------------------------------------------------------- модели

    static class Book implements Comparable<Book> {
        final String isbn;
        final String title;
        final String author;
        final int year;
        int copies;
        int available;
        int timesTaken;
        final Set<String> tags = new TreeSet<>();
        final LinkedList<String> waitList = new LinkedList<>();

        Book(String isbn, String title, String author, int year) {
            this.isbn = isbn;
            this.title = title;
            this.author = author;
            this.year = year;
        }

        @Override
        public int compareTo(Book other) {
            int byAuthor = author.compareTo(other.author);
            return byAuthor != 0 ? byAuthor : title.compareTo(other.title);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Book)) {
                return false;
            }
            return isbn.equals(((Book) o).isbn);
        }

        @Override
        public int hashCode() {
            return isbn.hashCode();
        }

        @Override
        public String toString() {
            return author + " — «" + title + "» (" + year + ")";
        }
    }

    static class Reader {
        final String id;
        final String type;
        final String name;
        final List<Loan> active = new ArrayList<>();
        final List<Loan> history = new ArrayList<>();
        long fineKopecks;

        Reader(String id, String type, String name) {
            this.id = id;
            this.type = type;
            this.name = name;
        }

        int limit() {
            return MAX_BOOKS.get(type);
        }
    }

    static class Loan {
        final Book book;
        final Reader reader;
        final int takenDay;
        final int dueDay;
        int returnedDay = -1;

        Loan(Book book, Reader reader, int takenDay, int dueDay) {
            this.book = book;
            this.reader = reader;
            this.takenDay = takenDay;
            this.dueDay = dueDay;
        }

        boolean isOpen() {
            return returnedDay < 0;
        }

        int overdueDays(int today) {
            int end = isOpen() ? today : returnedDay;
            return Math.max(0, end - dueDay);
        }
    }

    /** Свой Iterable: полка, которую можно обходить for-each. */
    static class Shelf implements Iterable<Book> {
        private final Book[] slots;
        private int size;

        Shelf(int capacity) {
            slots = new Book[capacity];
        }

        boolean put(Book book) {
            if (size == slots.length) {
                return false;
            }
            slots[size++] = book;
            return true;
        }

        @Override
        public Iterator<Book> iterator() {
            return new Iterator<Book>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < size;
                }

                @Override
                public Book next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException("полка закончилась");
                    }
                    return slots[index++];
                }
            };
        }
    }

    // ---------------------------------------------------------------- исключения

    static class LibraryException extends Exception {
        LibraryException(String message) {
            super(message);
        }
    }

    static class NotFoundException extends LibraryException {
        NotFoundException(String what) {
            super("не найдено: " + what);
        }
    }

    static class LimitExceededException extends LibraryException {
        LimitExceededException(Reader reader) {
            super("у читателя " + reader.name + " уже " + reader.active.size() + " книг(и) — лимит " + reader.limit());
        }
    }

    static class NoCopiesException extends LibraryException {
        NoCopiesException(Book book) {
            super("нет свободных экземпляров «" + book.title + "»");
        }
    }

    static class BadCommandException extends IllegalArgumentException {
        BadCommandException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- состояние

    static final Map<String, Book> catalog = new TreeMap<>();
    static final Map<String, Reader> readers = new HashMap<>();
    static final List<Loan> loans = new ArrayList<>();
    static final LinkedList<String> events = new LinkedList<>();
    static final Map<String, List<String>> index = new HashMap<>();
    static int today;
    static int failures;

    // ---------------------------------------------------------------- точка входа

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        int lineNumber = 0;
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().strip();
            lineNumber++;
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            try {
                execute(line);
            } catch (LibraryException e) {
                failures++;
                System.out.println("[" + lineNumber + "] отказ: " + e.getMessage());
            } catch (BadCommandException e) {
                failures++;
                System.err.println("[" + lineNumber + "] ошибка команды: " + e.getMessage());
            } catch (RuntimeException e) {
                failures++;
                System.err.println("[" + lineNumber + "] сбой: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        System.out.println("Итого отказов и ошибок: " + failures);
    }

    static void execute(String line) throws LibraryException {
        String[] p = line.split(" ", 2);
        String cmd = p[0];
        String rest = p.length > 1 ? p[1] : "";
        switch (cmd) {
            case "BOOK" -> addBook(rest);
            case "COPY" -> addCopies(rest);
            case "READER" -> addReader(rest);
            case "TAKE" -> take(rest);
            case "RETURN" -> giveBack(rest);
            case "RESERVE" -> reserve(rest);
            case "SEARCH" -> search(rest);
            case "TAGS" -> tag(rest);
            case "STATS" -> stats();
            default -> throw new BadCommandException("неизвестная команда «" + cmd + "»");
        }
    }

    // ---------------------------------------------------------------- команды

    static void addBook(String rest) {
        String[] halves = rest.split("\\|");
        if (halves.length != 2) {
            throw new BadCommandException("BOOK: нужен формат «isbn год автор | название»");
        }
        String[] head = halves[0].trim().split(" ", 3);
        if (head.length < 3) {
            throw new BadCommandException("BOOK: не хватает isbn, года или автора");
        }
        int year = Integer.parseInt(head[1]);
        Book book = new Book(head[0], halves[1].trim(), head[2].trim(), year);
        if (catalog.putIfAbsent(book.isbn, book) != null) {
            throw new BadCommandException("книга " + book.isbn + " уже в каталоге");
        }
        indexBook(book);
        log("добавлена книга " + book.isbn);
    }

    static void addCopies(String rest) throws NotFoundException {
        String[] p = rest.split(" ");
        Book book = requireBook(p[0]);
        int n = Integer.parseInt(p[1]);
        if (n <= 0) {
            throw new BadCommandException("COPY: количество должно быть положительным");
        }
        book.copies += n;
        book.available += n;
        while (book.available > 0 && !book.waitList.isEmpty()) {
            String readerId = book.waitList.removeFirst();
            log("бронь " + readerId + " на " + book.isbn + " может быть выдана");
            book.available--;
            book.available++;
            break;
        }
    }

    static void addReader(String rest) {
        String[] p = rest.split(" ", 3);
        if (p.length < 3) {
            throw new BadCommandException("READER: нужен id, тип и имя");
        }
        String type = p[1].toUpperCase();
        if (!READER_TYPES.contains(type)) {
            throw new BadCommandException("тип читателя должен быть одним из " + READER_TYPES);
        }
        readers.put(p[0], new Reader(p[0], type, p[2]));
    }

    static void take(String rest) throws LibraryException {
        String[] p = rest.split(" ");
        advanceDay(Integer.parseInt(p[0]));
        Reader reader = requireReader(p[1]);
        Book book = requireBook(p[2]);
        if (reader.active.size() >= reader.limit()) {
            throw new LimitExceededException(reader);
        }
        if (reader.fineKopecks > 50000) {
            throw new LibraryException("у читателя " + reader.name + " неоплаченный штраф " + money(reader.fineKopecks));
        }
        if (book.available == 0) {
            throw new NoCopiesException(book);
        }
        if (!book.waitList.isEmpty() && !book.waitList.getFirst().equals(reader.id)) {
            throw new LibraryException("книга забронирована другим читателем");
        }
        book.waitList.remove(reader.id);
        book.available--;
        book.timesTaken++;
        Loan loan = new Loan(book, reader, today, today + LOAN_DAYS.get(reader.type));
        reader.active.add(loan);
        loans.add(loan);
        System.out.println("день " + today + ": " + reader.name + " взял(а) " + book + " до дня " + loan.dueDay);
    }

    static void giveBack(String rest) throws LibraryException {
        String[] p = rest.split(" ");
        advanceDay(Integer.parseInt(p[0]));
        Reader reader = requireReader(p[1]);
        Book book = requireBook(p[2]);
        Optional<Loan> found = reader.active.stream().filter(l -> l.book.equals(book)).findFirst();
        Loan loan = found.orElseThrow(() -> new NotFoundException("выдача «" + book.title + "» у " + reader.name));
        loan.returnedDay = today;
        reader.active.remove(loan);
        reader.history.add(loan);
        book.available++;
        int overdue = loan.overdueDays(today);
        if (overdue > 0) {
            long fine = fineFor(reader.type, overdue);
            reader.fineKopecks += fine;
            System.out.println("день " + today + ": возврат с опозданием на " + overdue + " дн., штраф " + money(fine));
        } else {
            System.out.println("день " + today + ": " + reader.name + " вернул(а) «" + book.title + "» вовремя");
        }
        if (!book.waitList.isEmpty()) {
            System.out.println("  → книгу ждёт читатель " + book.waitList.getFirst());
        }
    }

    static void reserve(String rest) throws LibraryException {
        String[] p = rest.split(" ");
        Reader reader = requireReader(p[0]);
        Book book = requireBook(p[1]);
        if (book.waitList.contains(reader.id)) {
            throw new LibraryException(reader.name + " уже в очереди на «" + book.title + "»");
        }
        book.waitList.addLast(reader.id);
        System.out.println(reader.name + " в очереди на «" + book.title + "», позиция " + book.waitList.size());
    }

    static void tag(String rest) throws NotFoundException {
        String[] p = rest.split(" ", 2);
        Book book = requireBook(p[0]);
        String[] tags = p[1].toLowerCase().split(",");
        Arrays.sort(tags);
        book.tags.addAll(Arrays.asList(tags));
        for (String t : tags) {
            index.computeIfAbsent(t.trim(), k -> new ArrayList<>()).add(book.isbn);
        }
    }

    /** Поиск по словам названия, автору и тегам. */
    static void search(String word) {
        String key = word.toLowerCase().trim();
        List<String> isbns = index.getOrDefault(key, Collections.emptyList());
        List<Book> found = new ArrayList<>();
        // заложенный дефект «collection-lookup-in-loop»: contains по списку внутри цикла
        for (String isbn : isbns) {
            Book b = catalog.get(isbn);
            if (b != null && !found.contains(b)) {
                found.add(b);
            }
        }
        Collections.sort(found);
        if (found.isEmpty()) {
            System.out.println("поиск «" + word + "»: ничего");
            return;
        }
        System.out.println("поиск «" + word + "»: " + found.size());
        for (Book b : found.subList(0, Math.min(5, found.size()))) {
            System.out.println("  " + b + (b.available > 0 ? "" : " — нет в наличии"));
        }
    }

    // ---------------------------------------------------------------- отчёт

    static void stats() {
        System.out.println("===== Статистика на день " + today + " =====");
        System.out.println("Книг в каталоге: " + catalog.size() + ", читателей: " + readers.size() + ", выдач: " + loans.size());
        printPopular();
        printOverdue();
        printByDecade();
        printAuthors();
        printReaders();
        printShelf();
        printWaitLists();
        printTagCloud();
        printSimilarTitles();
        printEvents();
    }

    static void printPopular() {
        List<Book> books = new ArrayList<>(catalog.values());
        books.sort(Comparator.comparingInt((Book b) -> b.timesTaken).reversed().thenComparing(b -> b.isbn));
        System.out.println("Популярные:");
        Iterator<Book> it = books.iterator();
        int shown = 0;
        while (it.hasNext() && shown < 3) {
            Book b = it.next();
            if (b.timesTaken == 0) {
                break;
            }
            System.out.printf("  %-40s выдач: %d%n", b.title, b.timesTaken);
            shown++;
        }
        if (shown == 0) {
            System.out.println("  —");
        }
    }

    static void printOverdue() {
        List<Loan> overdue = new ArrayList<>();
        for (Loan l : loans) {
            if (l.isOpen() && l.overdueDays(today) > 0) {
                overdue.add(l);
            }
        }
        overdue.sort((a, b) -> Integer.compare(b.overdueDays(today), a.overdueDays(today)));
        System.out.println("Просрочено: " + overdue.size());
        for (Loan l : overdue) {
            System.out.println("  " + l.reader.name + ": «" + l.book.title + "» — " + l.overdueDays(today) + " дн.");
        }
    }

    static void printByDecade() {
        TreeMap<Integer, Integer> decades = new TreeMap<>();
        for (Book b : catalog.values()) {
            decades.merge(b.year / 10 * 10, 1, Integer::sum);
        }
        Formatter f = new Formatter();
        f.format("По десятилетиям:");
        for (Map.Entry<Integer, Integer> e : decades.entrySet()) {
            f.format(" %dе=%d", e.getKey(), e.getValue());
        }
        System.out.println(f);
        f.close();
        if (!decades.isEmpty()) {
            System.out.println("  самые старые: " + decades.firstKey() + "е, самые новые: " + decades.lastKey() + "е");
        }
    }

    /** Заложенный дефект «nested-loop-same-collection»: соавторы ищутся двойным циклом по каталогу. */
    static void printAuthors() {
        List<Book> all = new ArrayList<>(catalog.values());
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < all.size(); i++) {
            int same = 0;
            for (int j = 0; j < all.size(); j++) {
                if (all.get(i).author.equals(all.get(j).author)) {
                    same++;
                }
            }
            counts.put(all.get(i).author, same);
        }
        System.out.println("Авторы (книг в каталоге): " + counts);
    }

    static void printReaders() {
        List<Reader> list = new ArrayList<>(readers.values());
        list.sort(Comparator.comparing((Reader r) -> r.type).thenComparing(r -> r.id));
        System.out.println("Читатели:");
        for (Reader r : list) {
            // заложенный дефект «string-concat-in-loop»
            String titles = "";
            for (Loan l : r.active) {
                titles += "«" + l.book.title + "» ";
            }
            System.out.printf("  %-6s %-8s %-12s на руках %d, прочитано %d, штраф %s %s%n",
                    r.id, r.type, r.name, r.active.size(), r.history.size(), money(r.fineKopecks), titles.trim());
        }
    }

    static void printShelf() {
        Shelf shelf = new Shelf(5);
        for (Book b : new TreeSet<>(catalog.values())) {
            if (b.available > 0 && !shelf.put(b)) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder("Полка новинок:");
        for (Book b : shelf) {
            sb.append(" [").append(b.isbn).append(']');
        }
        System.out.println(sb);
    }

    /** Заложенный дефект «linked-list-index-access»: обход очереди LinkedList по индексу. */
    static void printWaitLists() {
        System.out.println("Очереди бронирования:");
        boolean any = false;
        for (Book b : catalog.values()) {
            if (b.waitList.isEmpty()) {
                continue;
            }
            any = true;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.waitList.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(b.waitList.get(i));
            }
            System.out.println("  " + b.isbn + ": " + sb);
        }
        if (!any) {
            System.out.println("  нет");
        }
    }

    static void printTagCloud() {
        Map<String, Integer> freq = new TreeMap<>();
        for (Book b : catalog.values()) {
            for (String t : b.tags) {
                freq.merge(t.trim(), 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        System.out.println("Теги: " + entries);
    }

    /** Похожие названия: общее значимое слово. Двойной цикл + Set.of стоп-слов. */
    static void printSimilarTitles() {
        List<String> pairs = new ArrayList<>();
        Book[] arr = catalog.values().toArray(new Book[0]);
        for (int i = 0; i < arr.length; i++) {
            Set<String> wordsA = words(arr[i].title);
            for (int j = i + 1; j < arr.length; j++) {
                Set<String> common = new TreeSet<>(wordsA);
                common.retainAll(words(arr[j].title));
                if (!common.isEmpty()) {
                    pairs.add(arr[i].isbn + "~" + arr[j].isbn + " " + common);
                }
            }
        }
        System.out.println("Похожие названия: " + (pairs.isEmpty() ? "нет" : pairs.size()));
        for (String s : pairs.subList(0, Math.min(10, pairs.size()))) {
            System.out.println("  " + s);
        }
    }

    static void printEvents() {
        System.out.println("Событий в журнале: " + events.size());
        ListIterator<String> it = events.listIterator(events.size());
        int shown = 0;
        while (it.hasPrevious() && shown < 3) {
            System.out.println("  " + it.previous());
            shown++;
        }
    }

    // ---------------------------------------------------------------- вспомогательное

    static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        for (String w : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (w.length() > 2 && !STOP_WORDS.contains(w)) {
                result.add(w);
            }
        }
        return result;
    }

    static void indexBook(Book book) {
        Set<String> keys = words(book.title);
        keys.addAll(words(book.author));
        for (String k : keys) {
            index.computeIfAbsent(k, x -> new ArrayList<>()).add(book.isbn);
        }
    }

    static Book requireBook(String isbn) throws NotFoundException {
        Book b = catalog.get(isbn);
        if (b == null) {
            throw new NotFoundException("книга " + isbn);
        }
        return b;
    }

    static Reader requireReader(String id) throws NotFoundException {
        return Optional.ofNullable(readers.get(id)).orElseThrow(() -> new NotFoundException("читатель " + id));
    }

    static void advanceDay(int day) {
        if (day < today) {
            throw new BadCommandException("день " + day + " раньше текущего " + today);
        }
        today = day;
    }

    /** Штраф в копейках: заложенный дефект «magic-number» и дублирование веток. */
    static long fineFor(String type, int overdueDays) {
        long fine;
        if (type.equals("STUDENT")) {
            fine = overdueDays * 500L;
            if (fine > 30000) {
                fine = 30000;
            }
        } else if (type.equals("SENIOR")) {
            fine = overdueDays * 250L;
            if (fine > 30000) {
                fine = 30000;
            }
        } else {
            fine = overdueDays * 1000L;
            if (fine > 30000) {
                fine = 30000;
            }
        }
        return fine;
    }

    static String money(long kopecks) {
        return String.format("%d,%02d ₽", kopecks / 100, kopecks % 100);
    }

    static void log(String event) {
        events.add("день " + today + ": " + event);
    }
}
