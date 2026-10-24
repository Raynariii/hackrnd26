# 01-shop-orders — интернет-магазин (~660 строк)

Справочники клиентов и товаров, заказы, промокоды, отмена, склад, отчёты по городам и категориям.

**Scope:** классы, интерфейсы, абстрактный класс и наследование, `static`-поля и вложенные классы, конструкторы,
`switch` по строкам с блоками, `for`/for-each/`while`, `break`/`continue`/`return`, `try/catch/finally`, `throw`,
своя иерархия проверяемых исключений (`ShopException` → `UnknownEntityException`, `OutOfStockException`) и
непроверяемое (`InvariantViolation`); `Scanner`, `printf`, `String.format`; `ArrayList`, `LinkedList`, `HashMap`,
`LinkedHashMap`, `TreeMap`, `TreeSet`, `Collections.sort`, анонимный `Comparator`, `Map.merge`, ссылки на методы.

**Заложенные дефекты:** `nested-loop-same-collection` (`printDuplicates`, O(n²)), `collection-lookup-in-loop`
(`printVipOrders`), `string-concat-in-loop` (`printCustomers`), `linked-list-index-access` (`printAuditTail`, цикл не с 0 —
авто-исправление обязано отказаться), `long-method` (`processCommand`), `magic-number`, `empty-catch` (`safeParse`),
`duplicate-code` (`totalQuantityForCity` / `totalQuantityForCategory`), god-class.

**Входы:** `01-basic` (полный сценарий), `02-errors` (все ветки ошибок, вывод в stderr), `03-empty`, `04-no-report`
(отчёт без команды REPORT), `bench-{2000,4000,8000}` (заказы растущего числа).
