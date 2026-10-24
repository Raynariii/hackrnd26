# 05-course-grades — онлайн-курс (~440 строк, бонусный scope)

Студенты и группы, задания четырёх типов со своими правилами опоздания, лучшая попытка, бонусы, взвешенный итог,
отметки, рейтинг, статистика по заданиям и группам, журнал событий.

**Scope (бонус):** `record` с компактным конструктором и валидацией, generic `record Pair<A, B>`, `enum` с полями,
конструктором и абстрактным методом в каждой константе, `EnumMap`, `sealed interface … permits` + switch с шаблонами
по record-ам (и `yield`), `instanceof` с шаблоном, дженерик-класс `Repository<T extends Identifiable>` с
`Predicate<? super T>` / `Function<? super T, ? extends R>`, обобщённые методы с ограничениями
(`<T extends Comparable<? super T>>`, `<K, V extends Number>`), `java.util.function` (`Predicate`, `Function`,
`BiConsumer`, `Supplier`, `UnaryOperator`, `BinaryOperator`), Stream API (`filter/map/sorted/limit/reduce/toList`,
`IntStream.range`, `mapToInt/mapToDouble`, `summaryStatistics`), `Collectors` (`groupingBy` с фабрикой и downstream,
`partitioningBy`, `mapping`, `counting`, `averagingDouble`, `toMap` с merge, `joining`, `toSet`), `Optional`
(`map`, `orElse`, `orElseGet`), `var`, текстовый блок + `formatted`, статический блок инициализации таблицы команд.

**Заложенные дефекты:** `collection-lookup-in-loop` (`printTaskStats`), `nested-loop-same-collection` (`printTopPairs`),
пересчёт `finalPercent` внутри компаратора (O(n log n · события)), god-class.

**Входы:** `01-basic` (два отчёта, общий и по группе), `02-errors` (валидация record, неизвестный enum, нехватка
аргументов), `03-empty`, `bench-{2000,4000,8000}`.
