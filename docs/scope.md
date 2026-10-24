# Поддержанный scope Java

Матрица «конструкция → граф → генерация → где проверено». Обязательные разделы 1–4 кейса Axenix и бонус.

**Как читать колонки**

- **Граф** — как конструкция видна в графе. *Обзор* — классы, члены и вызовы; *блок-схема* — тип блока из
  `contracts/graph.schema.json`. «В коде блока» значит, что выражение входит в код содержащего его блока
  (например, `Вызов` или `Операция`) и подсвечивается вместе с ним.
- **Генерация** — код печатается обратно. Нетронутый код всегда печатается байт-в-байт (`LexicalPreservingPrinter`):
  это проверяет `CorpusRoundTripTest` на всём корпусе. Операции над графом (`editNode`, `insertAfter`, `delete`,
  `move`, `wrapIf`, `wrapLoop`, `extractMethod`) затрагивают только свои AST-узлы, и результат каждой операции
  в тестах `GraphOperationsTest` проверяется компиляцией `javac`.
- **Где проверено** — примеры `corpus/` (`01`…`06`), `ScopeSample.java` (тест ядра), тесты модулей. Каждый пример
  корпуса автоматически проходит три проверки: разбор и печать (`CorpusRoundTripTest`), анализ (`CorpusAnalysisTest`),
  компиляцию и детерминированный запуск всех входов (`CorpusSmokeTest`).

Легенда: ✅ поддержано и проверено · 🟡 поддержано с оговоркой · ⬜ показывается узлом `raw` («код как есть»):
разбор не падает, код сохраняется, правки вокруг узла работают.

## 1. Язык и java.lang

| Конструкция | Граф | Генерация | Где проверено |
|---|---|---|---|
| Классы, вложенные `static`-классы, анонимные классы | ✅ обзор: `class`, члены, рёбра `contains` | ✅ | 01, 02, 03, ScopeSample |
| Интерфейсы, `default`-методы, абстрактные классы, наследование | ✅ обзор: `interface` / `class` | ✅ | 01 (`Priced`, `Entity`), 03 (`Account.formatted`) |
| Примитивы `byte` `short` `char` `int` `long` `float` `double` `boolean` | ✅ блок «Переменная» | ✅ | 03, 04, 06 |
| Обёртки (`Integer.valueOf`, кэш, `compare`, `parseInt`, `Double.parseDouble`) | ✅ в коде блока | ✅ | 01, 03 |
| Массивы 1D/2D, `arraycopy`, `Arrays.*` | ✅ «Переменная» / «Операция» | ✅ | 03 (`RATES[][]`, история), 04 (счётчики) |
| Арифметика, `%`, побитовые операции, сравнение, логика, тернарный оператор | ✅ в коде блока | ✅ | 03 (флаги `&`/`|=`, `Math.addExact`) |
| Приведение типов `(int)` `(long)` `(char)` `(short)` | ✅ | ✅ | 03, 04 (Цезарь) |
| `if` / `else if` / `else` | ✅ «Условие» с ветками Да/Нет | ✅ | все |
| `switch` (классический, со стрелками, по `String` / `char` / `int`), провал без `break` | ✅ «Выбор» → «Вариант» / «Иначе», ветка «нет совпадений» | ✅ | 01, 03, 04, 06 |
| switch-выражение (`yield`) | 🟡 в коде блока, внутрь не раскрывается | ✅ | 03, 05, ScopeSample |
| `for`, for-each, `while`, `do-while` | ✅ «Цикл»: тело, `loopBack`, выход | ✅ | все; `do-while` — 03 |
| `break` / `continue` / `return`, метки (`break loop`, `continue outer`) | ✅ «Выход», «Следующая итерация», «Возврат», рёбра к цели метки | ✅ | 03 (`loop:`), 04 (`outer:`) |
| Методы, конструкторы, `this(...)` / `super(...)`, поля, `static`, `final` | ✅ обзор: `method` / `constructor` / `field`; блок-схема для методов и конструкторов | ✅ | все |
| `static`-блок инициализации | 🟡 в граф не попадает (правится в редакторе кода; запрос роли 1) | ✅ | 03, 05 |
| `try` / `catch` (multi-catch) / `finally`, try-with-resources | ✅ «Попытка» → «Обработка» / «Всегда» | ✅ | 01, 03, 05, ScopeSample |
| `throw` | ✅ «Исключение» | ✅ | все |
| `Object`: `equals` / `hashCode` / `toString`, `getClass` | ✅ | ✅ | 01, 02, 03 |
| `String`, `StringBuilder`, `Character` | ✅ в коде блока | ✅ | 04 |
| `Math` | ✅ | ✅ | 03 |
| `System.out` / `err` / `in`, `System.exit` | ✅ блок «Вызов» | ✅ | 03 (коды выхода 2 и 3) |
| `assert` | ✅ «Операция (assert)» | ✅ | ScopeSample |
| `synchronized` | ⬜ `raw` (многопоточность вне scope) | ✅ | ScopeSample |
| Локальный класс или локальный record внутри метода | ⬜ `raw` | ✅ | ScopeSample |

## 2. Консольный ввод-вывод

| Конструкция | Граф | Генерация | Где проверено |
|---|---|---|---|
| `Scanner` (`nextLine`, `nextInt`, `hasNext*`), в том числе `new Scanner(System.in, StandardCharsets.UTF_8)` | ✅ | ✅ | все, 03 |
| `System.out.println` / `print` / `printf`, `String.format`, `formatted` | ✅ | ✅ | все |
| `java.util.Formatter` | ✅ | ✅ | 02, 03 |
| `StandardCharsets`, `getBytes(UTF_8)`, кириллица в stdin и stdout | ✅ | ✅ | 03, 04; песочница запускает JVM с `-Dfile.encoding=UTF-8`, тест `JavaSandboxTest` |
| `System.err` + код выхода | ✅ | ✅ | verify сравнивает stderr (без кадров стектрейса) и `exitCode` |

## 3. Коллекции java.util

| Конструкция | Граф | Генерация | Где проверено |
|---|---|---|---|
| `Collection`, `Iterable` (свой `Iterable` + анонимный `Iterator`), `Iterator`, `ListIterator` | ✅ | ✅ | 02 (`Shelf`) |
| `List` / `ArrayList` / `LinkedList` (очередь: `addLast`, `removeFirst`, `getFirst`) | ✅ | ✅ | 01, 02, 06 |
| `Set` / `HashSet` / `TreeSet`, `Map` / `HashMap` / `TreeMap` / `LinkedHashMap` / `EnumMap` | ✅ | ✅ | 01, 02, 05 |
| `Collections` (`sort`, `emptyList`, `unmodifiableCollection`), `Arrays` | ✅ | ✅ | 01, 02, 05 |
| `Comparator` (анонимный, лямбда, `comparing…thenComparing…reversed`), `Comparable` | ✅ | ✅ | 01, 02, 05 |
| `Optional` (`ofNullable`, `orElseThrow`, `map`, `orElse`) | ✅ | ✅ | 02, 05 |
| `List.of` / `Set.of` / `Map.of` | ✅ | ✅ | 02 |
| Стоимость операций коллекций для Big O (`ArrayList.contains` O(n), `HashSet.contains` O(1), `TreeMap.get` O(log n), сортировка O(n log n)) | ✅ метрика метода и находки | — | `MetricsTest`, `CorpusAnalysisTest` |

## 4. Исключения

| Конструкция | Граф | Генерация | Где проверено |
|---|---|---|---|
| Проверяемые исключения, `throws`, обязательный `catch` | ✅ | ✅ | 01, 02, 03, 04, 06 |
| Непроверяемые исключения (`IllegalArgumentException`, `IllegalStateException`, `ArithmeticException`, …) | ✅ | ✅ | все |
| Свои исключения через наследование (иерархии) | ✅ обзор: классы | ✅ | 01 (`ShopException` → 2 подкласса), 02 (3 подкласса + своё непроверяемое) |
| Цепочка причин (`new X(msg, cause)`), `Caused by` в stderr | ✅ | ✅ | 03; verify сохраняет `Caused by` при сравнении |

## Бонус

| Конструкция | Граф | Генерация | Где проверено |
|---|---|---|---|
| `record` (компактный конструктор, generic record) | 🟡 обзор: узел `record` и его методы; компоненты и компактный конструктор в обзоре пока не показаны (запрос роли 1) | ✅ | 05, ScopeSample |
| `enum` с полями, конструктором, абстрактными методами в константах | 🟡 обзор: `enum`, поля, конструктор и методы; константы и их тела не показаны | ✅ | 05 (`TaskType`, `Mark`) |
| `sealed interface … permits`, switch с шаблонами, `instanceof` с шаблоном | ✅ (switch-выражение — в коде блока) | ✅ | 05, ScopeSample |
| Дженерики: классы `<T extends X>`, методы `<T extends Comparable<? super T>>`, wildcard | ✅ | ✅ | 05 (`Repository<T>`, `maxOf`, `sumValues`) |
| Лямбды, ссылки на методы, `java.util.function` (`Predicate`, `Function`, `BiConsumer`, `Supplier`, `UnaryOperator`, `BinaryOperator`) | ✅ в коде блока; вызовы из лямбд дают рёбра `call` от метода | ✅ | 05 |
| Stream API, `IntStream`, `Collectors` (`groupingBy`, `partitioningBy`, `toMap`, `joining`, `counting`, `averagingDouble`, `mapping`) | ✅ в коде блока | ✅ | 05 |
| `var`, текстовые блоки | ✅ | ✅ | 05, ScopeSample |

## Анализ, исправления и проверка

| Возможность | Что поддержано | Где проверено |
|---|---|---|
| Правила дефектов | 10 правил: вложенные циклы по одной коллекции, `contains` / `indexOf` / `remove` в цикле, конкатенация в цикле, `LinkedList.get(i)` в цикле, глубокая вложенность, длинный метод, дублирование, пустой `catch`, «магические» числа, god-class | `AnalyzerRulesTest`, `CorpusAnalysisTest` |
| Big O | на каждый метод и для каждой находки «до / после» | `MetricsTest` |
| Авто-исправления | 4: `HashSet` вместо `List.contains`, `StringBuilder` вместо `+=`, for-each вместо `LinkedList.get(i)`, константа вместо числа. Каждое проходит quality gate (компиляция + дифф-тесты) | `FixEngineTest`, `FixAndDecomposeApiTest`, сквозной прогон на `06-demo-warehouse` |
| Декомпозиция | предложение разбиения на `Parser` / `Analyzer` / `Optimizer` / `Main` / `Utils` (без автоприменения) | `DecomposerTest` |
| Проверка поведения | дифф-тесты: версия 1 против текущей на входах корпуса, на входах прошлых запусков и на граничных; бенчмарк n / 2n / 4n; экспорт golden-master тестов JUnit | `VerifyServiceTest`, `ExportApiTest`, `CorpusSmokeTest` |

## Вне scope (по условиям кейса не оценивается)

Многопоточность (`synchronized` показывается узлом `raw`, `Thread` и `ExecutorService` не анализируются), рефлексия,
файлы (`java.nio.file`), сеть, `java.time`. Программа, которая читает файлы или ходит в сеть, всё равно
разбирается, показывается и правится, но в песочнице `runner` нет ни сети, ни доступной для записи файловой
системы за пределами `/tmp`.
