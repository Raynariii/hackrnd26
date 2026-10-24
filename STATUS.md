# STATUS — кто что делает

> Каждый агент в конце сессии обновляет **свою** секцию. Формат пункта: `- [ ] задача` / `- [x] задача (PR #N)`.
> Этот файл — источник правды по списку задач. Живая доска «кто что делает сейчас» — в Notion
> ([штаб](https://app.notion.com/p/402644883f4881619b48df4c0379694e) → «Задачи»), правила — в `CLAUDE.md`.
> Ближайшая веха: **H10 — интеграция №1** (полный путь на одном примере). Промежуточная сдача — **25.10 08:00**.

## Каркас (готово)
- [x] Maven multi-module: `core`, `analysis`, `runner`, `api`; `mvn -B verify` зелёный
- [x] Контракты: `contracts/graph.schema.json`, `contracts/openapi.yaml`
- [x] `core`: парсинг с сохранением форматирования, печать кода, overview-граф (типы + члены + contains), round-trip тест
- [x] `analysis`: модели `Finding`/`MethodMetrics`, интерфейс `Rule`, пример `LongMethodRule`
- [x] `runner`: компиляция и запуск javac/java с stdin, таймаутом, лимитом вывода, кириллицей; тесты
- [x] `api`: проекты в памяти с версиями, `POST /projects`, `GET /projects/{id}`, `PUT /source`, `GET /graph` (overview),
      `GET /diagnostics`, `POST /run` (через runner); остальное отвечает 501 `not_implemented`
- [x] Фронт: макет из презентации (дерево, холст React Flow + elkjs, анализ, Monaco, консоль/тесты), режим моков
- [x] Docker Compose (web, api, runner в изолированной сети), CI (backend, frontend, contracts, compose build)

## Роль 0 — лид
- [x] Ревизия архитектуры против ТЗ (LEAD.md §4), дыры G1–G10 разнесены по ролям
- [ ] Запросить у менторов тестовые монолиты организаторов и финальный формат сдачи (G8)
- [ ] Выдать доступ к приватному репозиторию проверяющим Axenix к 25.10 08:00 (или уточнить способ передачи)
- [ ] Провести интеграцию №1 (H10) и пересмотреть план по LEAD.md §5
**Блокеры:** —

## Роль 1 — ядро Java ↔ граф
- [x] `methodFlow`: блок-схема метода и конструктора (`MethodFlowBuilder`): variable, condition (Да/Нет), loop
      (for / for-each / while / do-while, `loopBody/loopBack/loopExit`, `while(true)` без выхода), call, operation,
      return, switch/case (провал без break, стрелочные case, «нет совпадений»), break/continue с метками,
      try-with-resources/catch/finally, throw. `parent` = контейнер (цикл, ветка условия, case, try, catch, finally).
      `meta.covers` — id выражений внутри блока: по нему `Finding.nodeIds` привязываются к блокам. 17 тестов (PR #5)
- [ ] overview: рёбра `call` (SymbolSolver, с откатом на имена)
- [x] узел `raw` для неподдерживаемых конструкций в блок-схеме (локальные классы, synchronized, yield; сбой разбора
      оператора откатывается в `raw`, а не роняет граф) (PR #5)
- [x] операции `editNode`, `insertAfter`, `delete` (`DefaultGraphOperations`, 27 тестов; PR — feat/core-graph-ops):
      цели — блоки блок-схемы (операторы, case, catch, finally, `:start`), члены обзора (метод, поле, тип) и id
      выражения из `Finding.nodeIds` (→ его оператор). Правка идёт текстом строго в границах AST-узлов, затем файл
      перепарсивается: нетронутый код байт-в-байт, вставка выровнена по соседям (табы, CRLF), ошибка → 422 без
      изменения модели. Каждый тест проверяет, что результат компилируется javac
- [ ] операции: `move`, `wrapIf`, `wrapLoop`, `extractMethod`
- [ ] round-trip тест на всём `corpus/`
- [x] откат на pretty-print, если LexicalPreservingPrinter падает (G6): `CodeGenerator.printReport` → `reformatted`
      (список файлов, напечатанных без сохранения форматирования); операции графа LPP для правок не используют
**Для фронта (роль 3):** находка → блок: блок, у которого `id` или `meta.covers` содержит любой из `Finding.nodeIds`.
`start` блок-схемы покрывает id метода (находки уровня метода, например long-method).
**Для фронта (роль 3), `POST /graph/ops`:** `editNode` у condition/loop/switch меняет только заголовок
(`code` = `x > 0` или `int i = 0; i < n; i++`), у case — метки (`1, 2` / `default`), у catch — параметр.
`insertAfter` после `<метод>:start` / case / catch / finally — в начало их тела, после контейнера — после всей
конструкции. Палитра: `kind` + короткий `code` (`condition` + `x > 0` → `if (x > 0) {}`, `loop` + `x : xs` →
for-each, `return` + `x`). `delete` принимает `nodeId` или `nodeIds`. После операции id меняются — перезапросите граф.
**Для роли 4:** `CodeGenerator.printReport(model).reformatted()` можно отдать в ответе как предупреждение (G6).
**Блокеры:** —

## Роль 2 — анализ и оптимизация
- [x] правила (10, `backend/analysis/.../rules`): вложенные циклы по одной коллекции, `contains/indexOf/remove` в цикле,
      конкатенация строк в цикле, `LinkedList.get(i)` в цикле, глубокая вложенность/цикломатика, длинный метод,
      дублирование (нормализованные фрагменты), пустой `catch`, «магические» числа, god-class — 18 тестов
- [x] метрики: цикломатическая сложность, максимальная вложенность, Big O на метод (число вложенных циклов ×
      таблица стоимости операций коллекций: `ArrayList.contains` O(n), `HashSet.contains` O(1), `TreeMap.get` O(log n),
      сортировка O(n log n)); `Finding.complexity` = «до/после» для всех найденных дефектов
- [x] авто-исправления для 4 правил (`FixEngine`): HashSet-индекс вместо `List.contains`, `StringBuilder` вместо `+=`,
      константа вместо «магического» числа, for-each вместо `LinkedList.get(i)`; каждая правка проверяется javac в тесте,
      отказ от небезопасных случаев (переменная объявлена в цикле, коллекция меняется, строку читают в цикле)
- [ ] `POST /fixes/{id}` в api: подключить `FixEngine.apply(files, findingId)` — запрос роли 4 (см. «Запросы»)
- [x] декомпозиция монолита: `Decomposer` предлагает разбиение на `Parser / Analyzer / Optimizer / Main` по именам
      методов и вызовам, с полями, которые нужно перенести (v1 — только предложение, без перемещения кода)
- [ ] `POST /decompose` в api (apply=false → предложение) — запрос роли 4 (см. «Запросы»)
- [ ] LLM-помощник (опц.)
**Известное ограничение:** LexicalPreservingPrinter не выравнивает отступы у вставленных операторов
(`var _index = new java.util.HashSet<>(...)` печатается на уровень выше) — код валиден и компилируется,
это косметика на полировку.
**PR:** [#3](https://github.com/Raynariii/hackrnd26/pull/3) — 48 тестов модуля `analysis` зелёные, CI зелёный
(backend, frontend, contracts, compose).
**Блокеры:** —

## Роль 3 — фронтенд
- [ ] блок-схема метода: группы для циклов/try (parent), подписи Да/Нет, стили по макету
- [ ] двусторонняя подсветка: клик по строке в Monaco → узел
- [ ] маркеры находок на узлах, клик по находке → узел
- [ ] палитра блоков (drag&drop) → `graph/ops`
- [ ] экран проверки: N/N тестов, «Поведение совпадает», график времени до/после
- [ ] Monaco без CDN (локальная загрузка для офлайн-демо)
**Блокеры:** ждём `methodFlow` (пока работаем на моках `npm run dev:mock`)

## Роль 4 — песочница, API, сборка
- [ ] `POST /internal/run-batch` в runner: компиляция один раз, много stdin (G2)
- [ ] `POST /verify`: дифф-тесты версия 1 vs текущая (входы из запроса + корпус + граничные), сравнение stdout/stderr/exit
      с нормализацией из контракта `VerifyCase` (G3)
- [ ] bench-входы `corpus/*/inputs/bench-*.txt` растущего размера (G4)
- [ ] монолиты организаторов → `corpus/` (G8, как только лид их получит)
- [ ] `docs/scope.md`: матрица «конструкция → граф / генерация / тест» по разделам 1–4 + бонус (G9)
- [ ] фаззинг статических методов через генерируемую обвязку (G5, P2)
- [ ] генерация JUnit golden-master + `GET /export` (zip)
- [ ] бенчмарк n, 2n, 4n
- [ ] SSE-консоль для `/run` (опц.)
- [ ] корпус: 5–8 монолитов ~1000 строк, покрытие scope 1–4 (+ бонус)
- [ ] `docs/`: архитектура, поддержанный scope, ограничения; README для жюри
- [ ] проверить `docker compose up --build` с нуля
**Блокеры:** —

## Запросы на изменения (контракты и чужие зоны)
Основное место — Notion «Вопросы и запросы». Сюда — только если Notion недоступен, и итог принятых изменений (со ссылкой на PR).

| Кто просит | Что | Кому | Статус |
|---|---|---|---|
| Роль 2 | `POST /api/projects/{id}/fixes/{findingId}`: вызвать `io.blueprints.analysis.FixEngine#apply(files, findingId)`, закоммитить новую версию и вернуть `ChangeResult` (state + diff); `FixNotApplicableException` → 409/422 с текстом причины. Контракт и контроллер — зона роли 4. | Роль 4 | Открыт |
| Роль 2 | `POST /api/projects/{id}/decompose`: `new Decomposer().propose(session.model())` → `DecomposeResult.proposal` (apply=false, v1); при apply=true пока 501 `not_implemented`. | Роль 4 | Открыт |

## Журнал интеграций
| Когда | Результат | Что сломалось |
|---|---|---|
| | | |
