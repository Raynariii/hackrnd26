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
- [x] Роль 2 разделена на подроли 2a–2d для параллельной работы нескольких агентов (D11, `PLAN.md` §6.1)
**Блокеры:** —

## Роль 1 — ядро Java ↔ граф
- [x] `methodFlow`: блок-схема метода и конструктора (`MethodFlowBuilder`): variable, condition (Да/Нет), loop
      (for / for-each / while / do-while, `loopBody/loopBack/loopExit`, `while(true)` без выхода), call, operation,
      return, switch/case (провал без break, стрелочные case, «нет совпадений»), break/continue с метками,
      try-with-resources/catch/finally, throw. `parent` = контейнер (цикл, ветка условия, case, try, catch, finally).
      `meta.covers` — id выражений внутри блока: по нему `Finding.nodeIds` привязываются к блокам. 17 тестов (PR #5)
- [x] overview: рёбра `call` (`CallGraph`, 4 теста; PR #12): метод / конструктор / поле →
      метод или конструктор проекта, `new X()` без явного конструктора → класс X, `this(...)`, вызовы из лямбд — от
      метода. SymbolSolver (типы проекта из памяти + JRE), при сбое — откат на имя + число аргументов: свой класс,
      `Класс.метод()`, `переменная.метод()` по объявленному типу. Библиотечные вызовы рёбер не дают. Повторные
      вызовы — одно ребро, `label` = «×N». ~0.3 с на 1300 строк
- [x] узел `raw` для неподдерживаемых конструкций в блок-схеме (локальные классы, synchronized, yield; сбой разбора
      оператора откатывается в `raw`, а не роняет граф) (PR #5)
- [x] операции `editNode`, `insertAfter`, `delete` (`DefaultGraphOperations`, 27 тестов; PR #7):
      цели — блоки блок-схемы (операторы, case, catch, finally, `:start`), члены обзора (метод, поле, тип) и id
      выражения из `Finding.nodeIds` (→ его оператор). Правка идёт текстом строго в границах AST-узлов, затем файл
      перепарсивается: нетронутый код байт-в-байт, вставка выровнена по соседям (табы, CRLF), ошибка → 422 без
      изменения модели. Каждый тест проверяет, что результат компилируется javac
- [x] операции `wrapIf`, `wrapLoop`, `move`, `extractMethod` (ещё 11 тестов, итого 38; PR #10):
      выделение — подряд идущие операторы одного блока (`nodeIds`, порядок не важен); `move` → `targetId` по правилам
      insertAfter, члены класса — после другого члена; `extractMethod` — private (static, если исходный static) метод
      `name`: параметры — читаемые локальные переменные, возврат — единственная переменная, нужная дальше;
      `throws` и type-параметры копируются. Отказ (422): return / break / continue наружу, >1 выходной переменной, `var`
- [x] round-trip тест на всём `corpus/` (`CorpusRoundTripTest`, PR — feat/core-corpus-roundtrip): каждая папка корпуса
      + `core/src/test/resources/scope/ScopeSample.java` (scope 1–4 + бонус: sealed/record/enum, generics, лямбды,
      Stream, switch-выражения с yield, text block, try-with-resources, multi-catch, метки, анонимный класс,
      instanceof-паттерны): разбор без ошибок, печать байт-в-байт, overview и блок-схема каждого метода согласованы,
      insertAfter `:start` на каждом методе. Новый пример корпуса проверяется автоматически; `raw`-конструкции
      печатаются в лог теста (сейчас только `synchronized`) — для `docs/scope.md` (роль 4)
- [x] откат на pretty-print, если LexicalPreservingPrinter падает (G6): `CodeGenerator.printReport` → `reformatted`
      (список файлов, напечатанных без сохранения форматирования); операции графа LPP для правок не используют
- [x] AST-хелперы фаззинга статических методов (G5; `io.blueprints.core.fuzz.FuzzHarness`, 4 теста; PR — feat/core-fuzz-helpers):
      `scan` — статические методы с параметрами int/long/double/boolean/char (+обёртки), String, одномерные массивы,
      `List<Integer|String>`, varargs; остальные — в `skipped` с причиной. `common(baseline, current)` — цели с той же
      сигнатурой в обеих версиях. `generate(targets, n)` — класс `BlueprintsFuzzHarness` (reflection, работает и с
      private): граничные значения + Random с фиксированным seed, строка на вызов `имя(args) -> результат | throws
      Тип: msg | timeout` (500 мс на вызов). Тест: рефакторинг через граф (extractMethod) даёт тот же вывод, смена
      условия — расхождение ровно в строках этого метода
**Для фронта (роль 3):** находка → блок: блок, у которого `id` или `meta.covers` содержит любой из `Finding.nodeIds`.
`start` блок-схемы покрывает id метода (находки уровня метода, например long-method).
**Для фронта (роль 3), `POST /graph/ops`:** `editNode` у condition/loop/switch меняет только заголовок
(`code` = `x > 0` или `int i = 0; i < n; i++`), у case — метки (`1, 2` / `default`), у catch — параметр.
`insertAfter` после `<метод>:start` / case / catch / finally — в начало их тела, после контейнера — после всей
конструкции. `wrapIf`/`wrapLoop`/`extractMethod` берут `nodeIds` (подряд в одном блоке) + `code` / `name`,
`move` — `nodeIds` + `targetId`. Палитра: `kind` + короткий `code` (`condition` + `x > 0` → `if (x > 0) {}`, `loop` + `x : xs` →
for-each, `return` + `x`). `delete` принимает `nodeId` или `nodeIds`. После операции id меняются — перезапросите граф.
`wrapIf`/`wrapLoop` отказывают (422), если выделение объявляет переменную, нужную после блока.
**Для роли 4 (фаззинг в /verify, fuzz=true):** `targets = FuzzHarness.common(baselineModel, currentModel)`,
`h = FuzzHarness.generate(targets, 20)`; добавить `h.file()` к файлам обеих версий, запустить в runner с
`mainClass = h.mainClass()` и пустым stdin, сравнить stdout (нормализация `@HASH` уже в контракте) — один VerifyCase
«fuzz» или по кейсу на метод (строки начинаются с имени метода).
**Для роли 4:** `CodeGenerator.printReport(model).reformatted()` можно отдать в ответе как предупреждение (G6).
**Блокеры:** —

## Роль 2 — анализ и оптимизация
> Роль делится на подроли **2a / 2b / 2c / 2d** — несколько агентов работают параллельно (D11). Зоны по файлам — `CLAUDE.md`,
> правила (worktree, реестры, слияние по одному) — `PLAN.md` §6.1. Каждый агент правит **только свою подсекцию** ниже.
> Захват задачи: допишите к пункту `(в работе: 2x, ветка …)` отдельным маленьким коммитом или карточкой в Notion.

**Сделано до разделения** (53 теста модуля зелёные; PR [#3](https://github.com/Raynariii/hackrnd26/pull/3),
[#4](https://github.com/Raynariii/hackrnd26/pull/4), [#9](https://github.com/Raynariii/hackrnd26/pull/9), #11, #15):
- [x] правила (10, `rules/`): вложенные циклы по одной коллекции, `contains/indexOf/remove` в цикле, конкатенация строк
      в цикле, `LinkedList.get(i)` в цикле, глубокая вложенность/цикломатика, длинный метод, дублирование, пустой `catch`,
      «магические» числа, god-class — 18 тестов
- [x] метрики: цикломатика, вложенность, Big O на метод (вложенность циклов × таблица стоимости операций коллекций);
      `Finding.complexity` = «до/после»
- [x] авто-исправления для 4 правил (`FixEngine`): HashSet-индекс, `StringBuilder`, константа, for-each вместо
      `LinkedList.get(i)`; каждая правка проверяется javac в тесте, небезопасные случаи — отказ
- [x] декомпозиция v1: `Decomposer` предлагает разбиение на `Parser / Analyzer / Optimizer / Main` (без перемещения кода)
- [x] прогон по всему `corpus/` (`CorpusAnalysisTest`), стык с ядром (`FindingNodeBindingTest`), устойчивость (PR #4)
- [x] `POST /fixes/{id}` (с quality gate) и `POST /decompose` (предложение) подключены в api ролью 4 (PR #21, #23)

### 2a — правила, метрики, общие файлы модуля
- [x] **P0, разблокер — первым, отдельным PR (PR #29).** Чисто механический рефакторинг без изменения поведения
      (все 53 старых теста зелёные без правок ассертов, публичный API модуля прежний):
      `rules/Rules.java` — реестр правил, `Analyzer.defaultRules()` делегирует ему;
      `fixes/Fixer` (интерфейс: `kind()`, `refusalReason(model, finding)`, `apply(...)`, `supports(...)` по умолчанию)
      + `fixes/Fixers.java` — реестр, 4 ветки `switch` из `FixEngine` разнесены по классам `fixes/*Fix.java`;
      `FixEngine` — фасад, диспетчеризует по `Fix.kind`, а не по `finding.rule()`;
      `Analyzer` выставляет `Fix.autoApplicable` по `Fixers.supports(model, finding)` — тем же проверкам, что и
      `apply` (закрыт запрос роли 4), а не по правилу;
      пакет `decompose/` для внутренностей `Decomposer` (сам `Decomposer` — фасад, record'ы и `propose(model)` прежние).
      **Сделано:** 2b/2c разблокированы — своё исправление = `Fixer` + строка в `fixes/Fixers`, стратегия — файл
      в `decompose/`, без правки общих файлов. Инвариант «флаг = реальное применение» закреплён
      `AnalyzerFixApplicabilityTest`. Пересечение: PR #27 (`feat/analysis-fix-safety`, та же задача через правила +
      `FixSafety`) — предложено слить #29 первым и ребейзнуть #27; `FixSafety` заменён реестром, полезные
      не-пересекающиеся части (словарь декомпозиции, тесты) — отдельным PR после ребейза.
- [ ] P1 Big O на фрагмент (выделение блоков в блок-схеме), не только на метод — `PLAN.md` §4
- [x] P1 стоимость Stream API и лямбд в `CostModel` (бонус scope; PR #34): терминальные операции
      (`collect`/`toList`/`count`/`sum`/`forEach`/`anyMatch`/`findFirst`/…) — один проход O(n); `sorted` — O(n log n);
      лямбда в `filter/map/anyMatch/forEach/collect` и коллекторных фабриках выполняется на каждом элементе,
      поэтому `list.contains` в лямбде потока — O(n²), поток с такой лямбдой в цикле — O(n³). Не раздуваем оценку
      там, где лямбда вызывается 0–1 раз (`Optional.map/filter`) или это `Math.min/max`. +6 тестов в `MetricsTest`
- [ ] P1 прогон по монолитам организаторов, как только они появятся в `corpus/` (G8): нет падений и явных ложных находок
      **Предпроверка (24.10, ветка `feat/corpus-monoliths`, PR #28):** 6 монолитов ~200–800 строк — разбор без ошибок,
      метрики по всем методам, 7–20 находок на монолит, все `Finding.nodeIds` привязаны к узлам. Шум: диапазоны
      `magic-number` в цепочках `else-if` перекрывались (исправлено в PR #32 — находка указывает на условие).
      Осталось: прогон на монолитах организаторов, когда лид их получит.
- [ ] P2 новые правила: `Pattern.compile` / `String.format` в цикле; рекурсия без мемоизации (экспоненциальная оценка)
**Блокеры:** —

### 2b — авто-исправления
- [ ] P1 (после 2a-P0) авто-исправление для `nested-loop-same-collection`: индекс `HashMap`/`HashSet` по ключу, отказ в
      небезопасных случаях; тест — javac + вывод до/после совпадает
- [ ] P1 отступы вставленных операторов (`var _index = …` печатается на уровень выше — «Известное ограничение»)
- [ ] P1 тест на корпусе: каждое `autoApplicable` исправление на `corpus/` применяется и компилируется
- [ ] P2 `extract-method` для `long-method` / `duplicate-code` через `GraphOperations.extractMethod` ядра (роль 1)
**Известное ограничение:** LexicalPreservingPrinter не выравнивает отступы у вставленных операторов — код валиден.
**Блокеры:** —

### 2c — декомпозиция монолита
- [ ] P1 (после 2a-P0) `apply=true` v1 (G7): перенос static-методов и static-вложенных классов в новые классы,
      вызовы квалифицируются; результат компилируется (тест javac) и проходит дифф на корпусе. Вход/выход — `List<SourceFile>`,
      как у `FixEngine`; подключение `apply=true` в `POST /decompose` (сейчас 501) — запрос роли 4
- [ ] P1 предложение по графу зависимостей методов/полей (label propagation), имена — только как подсказка
- [ ] P2 метрика качества разбиения (вызовы между предложенными классами) — в `notes` для жюри
**Блокеры:** —

### 2d — LLM-помощник (опц., первый на урезание по LEAD.md §5)
- [ ] P2 `llm/`: интерфейс советчика + реализация через HTTP-клиент; ключ только из переменной окружения api, не в git;
      предложение показывается только после quality gate (D6). Без ключа — модуль молча выключен, тесты без сети
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
- [x] `POST /internal/run-batch` в runner: javac один раз, затем `java` на каждом stdin; из вывода убраны путь
      временной папки и `Picked up JAVA_TOOL_OPTIONS` (переменные `*_JAVA_OPTIONS` не передаются в процесс); `mainClass`
      проверяется как FQN, stdout/stderr читаются не больше лимита (G2, PR #19)
- [x] `POST /verify` (G3, PR #19): эталон v1 и текущая версия — по одному run-batch на одних входах.
      Входы: `inputs` запроса + `corpus/<пример>/inputs/*.txt` (если файлы проекта совпадают с примером корпуса) +
      stdin прошлых `/run` + 6 граничных (`fuzz`, по умолчанию вкл.); дубли убираются, ≤ 40. Нормализация
      (`OutputNormalizer`): `@hex` → `@HASH`, `\r\n`, пути песочницы, строки JVM; stderr — без кадров стектрейса
      (тип + сообщение + `Caused by` остаются). `mismatch` — что и где разошлось («stdout: строка 1: ожидалось «…»,
      получено «…»; exitCode: …»). Текущая не компилируется → 200 с `compiled=false`; эталон не компилируется → 422
      `baseline_compile_error`. `benchmark=true` → `benchmarkInputs` или `bench-<n>.txt` из корпуса, время до/после.
      Сквозная проверка (api + runner, `00-hello`): эквивалентная правка — 8/8 ✓ за ~2,7 с; изменённая логика и
      исключение — точный дифф. В compose корпус монтируется в api (`./corpus:/corpus:ro`)
- [x] `POST /fixes/{findingId}` (запрос роли 2, PR — `feat/api-fixes-decompose`): `FixEngine.apply` → **quality gate**
      (`QualityGate`: новая версия vs текущая через verify — компиляция + дифф-тесты) → коммит новой версии.
      Ответ `ChangeResult` = state + unified diff + `description` + `verify` (отчёт gate). Ошибки 422:
      `fix_not_applicable`, `quality_gate_failed` (сессия не меняется), `parse_error`; 409 — устаревшая версия.
      Сквозная проверка на `OrdersMonolith`: StringBuilder, for-each по LinkedList, константы — 3 исправления подряд, gate 6/6
- [x] `POST /decompose` (запрос роли 2): `Decomposer.propose` → `proposal` + `notes`; `apply=true` → 501 (G7)
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
**Для фронта (роль 3):** `POST /verify` работает по контракту `VerifyReport` (тело `{}` допустимо). Готовы: `cases[].name`
(«corpus/…/01.txt», «Вход №1», «Граничный: …», «Бенчмарк: n = …»), `mismatch` — текст для панели «Тесты»,
`benchmark[]` — для графика до/после (`{"benchmark": true}`; пока bench-входы есть только при передаче `benchmarkInputs`).
**Блокеры:** —

## Запросы на изменения (контракты и чужие зоны)
Основное место — Notion «Вопросы и запросы». Сюда — только если Notion недоступен, и итог принятых изменений (со ссылкой на PR).

| Кто просит | Что | Кому | Статус |
|---|---|---|---|
| Роль 2 | `POST /api/projects/{id}/fixes/{findingId}`: вызвать `io.blueprints.analysis.FixEngine#apply(files, findingId)`, закоммитить новую версию и вернуть `ChangeResult` (state + diff); `FixNotApplicableException` → 409/422 с текстом причины. Контракт и контроллер — зона роли 4. | Роль 4 | Сделано — `feat/api-fixes-decompose` |
| Роль 4 | В `GET /diagnostics` у `collection-lookup-in-loop` стоит `fix.autoApplicable=true`, но `FixEngine.apply` отказывает (коллекция меняется в цикле — `00-hello`, `OrdersMonolith`). Кнопка «Применить» в UI обещает то, чего нет: выставлять `autoApplicable` по тем же проверкам, что и `apply`. | Роль 2 | Сделано — PR #29: `autoApplicable` = `Fixers.supports(model, finding)` (те же проверки, что и `apply`), текст отказа у 422 совпадает |
| Роль 2 | `POST /api/projects/{id}/decompose`: `new Decomposer().propose(session.model())` → `DecomposeResult.proposal` (apply=false, v1); при apply=true пока 501 `not_implemented`. | Роль 4 | Сделано — `feat/api-fixes-decompose` |

## Журнал интеграций
| Когда | Результат | Что сломалось |
|---|---|---|
| 24.10 ~09:45 (роль 1, локально: runner + api + фронт `feat/frontend-line-node`) | ✅ загрузка `ScopeSample.java` → overview (26 contains + 16 call) → блок-схемы всех 19 методов → диагностика (5 находок, все привязаны к узлам) → запуск в runner → 9 операций графа подряд (insertAfter, extractMethod, wrapIf, wrapLoop, editNode ×3, move) → повторный запуск: **вывод совпал**. Фронт рисует overview и блок-схемы на реальных данных без ошибок в консоли | 🔴 `POST /verify` — 501 (роль 4): шаг «сравнение» есть только вручную. 🟡 `wrapIf/wrapLoop` вокруг объявления, нужного дальше, ломали компиляцию; тип в `extractMethod` печатался без пробелов — исправлено (роль 1). 🟡 Фронт: overview на 36 узлов — мелкая плоская лента, рёбра call накладываются; в `client.ts` нет `graph/ops` — правка на графе из UI пока недоступна (роль 3). 🟡 Вывод javac/java в runner может начинаться со строки `Picked up JAVA_TOOL_OPTIONS`, если переменная задана в окружении — нормализация verify должна её отбрасывать (роль 4) |
| 24.10 ~12:30 (роль 1, локально: runner + api из main) | ✅ **полный путь через api**: `ScopeSample.java` → `/verify` v1↔v1 8/8 → 4 операции графа (extractMethod, wrapIf, move, insertAfter) → `/verify` 8/8 «поведение совпадает» → editNode с изменением поведения → `/verify` 0/8 с точной строкой расхождения («строка 4: ожидалось «3workday3», получено «3always3»») → `/fixes` (magic-number) применился. Масштаб: синтетический монолит 1300 строк — граф 0,16 с, диагностика 0,22 с, операция 1,2 с, `/verify` 8 с на 6 входах | 🟡 Фронт: в `client.ts` всё ещё нет `graph/ops` — правка на графе только через api (роль 3). 🟡 `/verify` fuzz=true пока гоняет только граничные stdin-входы — `FuzzHarness` (PR #20) не подключён (роль 4, запрос в Notion). 🟡 В `corpus/` только `00-hello` (25 строк) — нужны монолиты ~1000 строк (роль 4 / G8) |
