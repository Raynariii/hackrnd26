# 07-logistics — служба доставки (~1100 строк, стресс-пример)

Карта дорог (Дейкстра, Флойд — Уоршелл, BFS), курьеры трёх видов транспорта, заказы с тарифами и промокодами,
жадное распределение, отчёты. Пример нарочно насыщен конструкциями, которые трудно разобрать, показать графом и
безопасно править, — чтобы находить проблемы всех слоёв: разбор, графы, анализ, исправления, декомпозиция, verify.

**Scope (разделы 1–4 + бонус):**
- классы: вложенные static, **внутренний (не static) `Courier`** с блоком `{}` и доступом к полю внешнего класса,
  анонимный `Iterator`, свой `Iterable` (кольцевой буфер), `AutoCloseable` (`ReportSection`), `equals/hashCode/
  toString/compareTo`, интерфейс с `default` и `static` методами, `@FunctionalInterface`;
- **sealed interface + record** (`Discount` → `Percent/Fixed/FreeKm`), компактные конструкторы с проверками и
  переприсваиванием параметра, **switch с образцами и `when`**, **локальный record** внутри метода;
- **enum с телами констант** и абстрактным методом (`Tariff`), enum с полями и конструктором, switch по enum,
  `EnumMap`;
- `static {}` с неизменяемой картой псевдонимов, text block, `var`-подобные цепочки, `instanceof` с образцами;
- управление: метка `outer:` с `continue outer`, старый switch с **провалом** и блоками `case X: { … }`,
  switch-выражение с `yield`, `do … while`, тернарные цепочки, битовые операции (`|=`, `<<`, `>>>`, `^`, `&`);
- исключения: своё проверяемое с цепочкой причины, непроверяемое, наследник проверяемого, `throws`, multi-catch,
  try-with-resources, `Math.addExact` + `ArithmeticException`, `NumberFormatException`, код выхода 3 при ошибках;
- коллекции и API: `PriorityQueue` с составным компаратором, `ArrayDeque`, `TreeMap`, `LinkedHashMap/Set`,
  `LinkedList`, `Collections.unmodifiableMap`, `List.copyOf`, `Map.entry`, `Optional.ifPresentOrElse`,
  Stream (`groupingBy` с `EnumMap`, `partitioningBy`, `summarizingInt`, `joining`, `summaryStatistics`),
  `StringJoiner`, `String.format(Locale.ROOT, …)`, `printf`, `Scanner(System.in, UTF_8)`, кириллица;
- дженерики: класс `Ledger<K extends Comparable<K>, V extends Number>`, метод `<T extends Comparable<? super T>>`,
  `@SafeVarargs` с wildcard.

**Заложенные дефекты (для анализатора и авто-исправлений):**

| Метод | Дефект |
|---|---|
| `assign` | `pending.contains(…)` по `ArrayList` в цикле; глубокая вложенность; Дейкстра в двойном цикле |
| `exportCsv` | `csv += …` в цикле + `String.format` в цикле |
| `auditLength` | `LinkedList.get(i)` в цикле с 0 — исправим for-each |
| `printAudit` | `LinkedList.get(i)` в цикле **не с 0** — авто-исправление обязано отказаться или gate должен его отклонить |
| `suspiciousDuplicates` | вложенные циклы по одному списку O(n²) |
| `countPromoLikeIds` | `Pattern.compile` в цикле |
| `boxings` | рекурсия без мемоизации (рядом — `boxingsMemo` для сравнения) |
| `totalWeightFor` / `totalKmFor` | дублирование |
| `process` | длинный метод (большой switch), магические числа (`2000`, `50`) |
| `distanceMatrix` | тройной цикл O(n³) — честная сложность, не дефект |
| `Main` | god-class |

**Входы:** `01-basic` (полный сценарий, все отчёты), `02-errors` (все ветки ошибок, код выхода 3), `03-empty`,
`04-edge` (псевдонимы, регистр, недостижимые пункты, платная дорога и велосипед, почти-дубли), `bench-{1000,2000,4000}`.
