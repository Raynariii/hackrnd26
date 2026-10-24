# 06-demo-warehouse — склад (~200 строк, главный пример для демо)

Приёмка товаров, движения остатков, сканирование, сверка, выгрузка CSV. Три горячих участка O(n²), каждый
исправляется автоматически и проходит quality gate:

| Метод | Дефект | Исправление | Сложность |
|---|---|---|---|
| `reconcile` | `registered.contains(sku)` в цикле по сканам | индекс `HashSet` | O(n²) → O(n) |
| `exportCsv` | `csv += …` в цикле | `StringBuilder` | O(n²) → O(n) |
| `movementTotals` | `movements.get(i)` у `LinkedList` | for-each | O(n²) → O(n) |

Проверено сквозь api + runner: три исправления подряд (gate 9/9 на каждом), verify v1 против v4 — 12/12,
бенчмарк n = 5000 / 10000 / 20000: ×1,5 / ×2,3 / ×6,5 (время растёт квадратично до исправлений и линейно после).

**Scope:** классы, `static`, switch со стрелками, `try/catch` с multi-catch, своё проверяемое исключение, `Scanner`,
`printf`, `Float.parseFloat`, `Math.round`, `Arrays.copyOfRange`, `String.join/split`, `LinkedHashMap`, `TreeMap`,
`computeIfAbsent`, `long[]` как аккумулятор, `LinkedList`.

**Входы:** `01-basic`, `02-errors`, `03-empty`, `bench-{5000,10000,20000}`.
