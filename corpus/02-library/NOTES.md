# 02-library — городская библиотека (~630 строк)

Каталог, читатели трёх типов, выдача/возврат по дням, очередь бронирования, штрафы, поиск по индексу слов, отчёт.

**Scope (акцент — раздел 3 и 4):** `List.of` / `Set.of` / `Map.of`, `ArrayList`, `LinkedList` (очередь: `addLast`,
`removeFirst`, `getFirst`, `listIterator` назад), `HashSet`, `TreeSet`, `HashMap`, `TreeMap` (`firstKey`/`lastKey`),
свой `Iterable` с анонимным `Iterator` и `NoSuchElementException`, `Comparable` + `equals`/`hashCode`,
`Comparator.comparing…thenComparing…reversed`, `Map.Entry.comparingByValue`, `Collections.sort/emptyList`,
`Arrays.sort/asList`, `Optional` (`ofNullable`, `orElseThrow`), `java.util.Formatter`; иерархия проверяемых
исключений (`LibraryException` → `NotFoundException`, `LimitExceededException`, `NoCopiesException`), своё
непроверяемое (`BadCommandException extends IllegalArgumentException`), `throws` и обязательный `catch`,
switch-выражение со стрелками.

**Заложенные дефекты:** `nested-loop-same-collection` (`printAuthors`, `printSimilarTitles`), `collection-lookup-in-loop`
(`search` — список меняется в цикле, авто-исправление должно отказаться), `string-concat-in-loop` (`printReaders`),
`linked-list-index-access` (`printWaitLists`, `get(i)` по полю объекта), `magic-number`, `duplicate-code` (`fineFor`).

**Входы:** `01-basic`, `02-errors` (отказы и ошибки команд), `03-limits` (лимит книг, штрафы), `04-empty-library`,
`bench-{2000,4000,8000}`.
