# Java Blueprints — визуальная среда программирования

Веб-инструмент, который разбирает Java-монолит в граф, позволяет исследовать и править программу как код и как
блок-схему (синхронно в обе стороны), находит дефекты и оценивает сложность (Big O), улучшает код и
**автоматическими тестами доказывает, что поведение программы сохранилось**.

Кейс Axenix, хакатон РнД, осень 2026.

## Быстрый запуск (Docker)

Нужен Docker с Compose v2. Интернет нужен только для сборки образов.

```bash
git clone https://github.com/Raynariii/hackrnd26.git && cd hackrnd26
cp .env.example .env
docker compose up --build
```

Откройте http://localhost:3000 → «Открыть .java» (или перетащите файл в окно) → `corpus/06-demo-warehouse/Main.java`.

| Сервис | Порт | Назначение |
|---|---|---|
| web | 3000 | интерфейс (nginx), проксирует `/api` |
| api | 8080 | REST API (`contracts/openapi.yaml`) |
| runner | — | песочница компиляции и запуска: доступна только из `api`, без сети, read-only FS, лимиты |

## Сценарий демо (≈ 5 минут)

Пример — `corpus/06-demo-warehouse` (склад, ~200 строк, три горячих участка O(n²)).

1. **Загрузить.** «Открыть .java» на стартовом экране (или иконка папки в левой полосе, или перетащить файл в окно) →
   обзорный граф: классы, методы, поля, рёбра вызовов; слева «Проводник» со структурой кода, справа «Проблемы».
2. **Исследовать.** **Двойной** клик по методу `reconcile` (на графе или в «Проводнике») → блок-схема: условия Да/Нет,
   циклы, вызовы. Клик по блоку → подсветка строк в редакторе, и наоборот; в шапке инспектора — Big O выделения.
3. **Диагноз.** Панель «Проблемы» справа: `contains` в цикле, конкатенация строк в цикле, `LinkedList.get(i)` в цикле —
   у каждой находки строка, узел графа, объяснение, сложность O(n²) → O(n) и метка важности («Критично» / «Важно» /
   «Мелочь»). Клик по карточке раскрывает её.
4. **Улучшить.** «Исправить автоматически» в карточке → исправление проходит quality gate (компиляция + дифф-тесты
   против предыдущей версии) → код и граф обновились, баннер с диффом и «Отменить», сразу открывается проверка против v1.
5. **Править на графе.** Выделите блок → инспектор слева: «Изменить», «Вставить после», «Обернуть в if / цикл»,
   «Вынести в метод», «Переместить», «Удалить» (Shift/Ctrl+клик — несколько блоков подряд). Или перетащите вид блока
   из палитры «Блоки» на блок схемы. Код синхронизируется.
6. **Запустить.** «Запуск» в верхней панели (Shift+F10); stdin — поле под консолью (иконка терминала в левой полосе);
   вывод идёт по мере работы программы.
7. **Проверить.** «Проверить поведение» → версия 1 против текущей: сборка ✓, N/N случаев ✓, «Поведение совпадает»,
   время до/после на bench-входах (n = 5000 / 10000 / 20000: ускорение ×1,5 / ×2,3 / ×6,5).
8. **Разбить.** Вкладка «Разбиение» → «Предложить разбиение» → «Применить разбиение»: новая версия появляется,
   только если поведение не изменилось.
9. **Экспорт.** Иконка загрузки в левой полосе (или «Скачать с JUnit-тестами» в отчёте проверки) → zip с текущей
   версией и golden-master тестами JUnit → `mvn test`.

Правая панель сворачивается стрелкой в её шапке — граф и код становятся шире; тема — переключатель внизу левой полосы.

То же через API (без UI):

```bash
API=http://localhost:8080/api
ID=$(jq -n --rawfile c corpus/06-demo-warehouse/Main.java '{name:"warehouse",files:[{path:"Main.java",content:$c}]}' \
     | curl -s -H 'Content-Type: application/json' -d @- $API/projects | jq -r .id)
curl -s $API/projects/$ID/diagnostics | jq '.findings[] | {rule, startLine, complexity}'
F=$(curl -s $API/projects/$ID/diagnostics | jq -r '[.findings[] | select(.rule=="collection-lookup-in-loop")][0].id')
curl -s -H 'Content-Type: application/json' -d '{"version":1}' $API/projects/$ID/fixes/$F | jq '{version: .state.version, gate: .verify.passed}'
curl -s -H 'Content-Type: application/json' -d '{"benchmark":true}' $API/projects/$ID/verify | jq '{behaviorPreserved, passed, total, benchmark}'
curl -s -o warehouse.zip $API/projects/$ID/export && unzip -q warehouse.zip && (cd warehouse-v2 && mvn -q test)
```

Требуется Java 21+, Maven 3.9+, Node 22+.

```bash
cd backend && mvn -B verify                         # сборка и все тесты
cd backend && mvn -B -pl runner -am spring-boot:run # песочница :8090
cd backend && mvn -B -pl api -am spring-boot:run    # API :8080 (корпус берётся из ../../corpus)
cd frontend && npm ci && npm run dev                # UI :5173, проксирует /api на :8080
```

Фронт без бэкенда: `cd frontend && npm run dev:mock`. Проверка корпуса вручную: `corpus/check.sh`.

> Вне Docker `runner` запускает `javac`/`java` прямо на машине, без изоляции контейнера. Для чужого кода
> используйте `docker compose`.

## Структура

```
contracts/   graph.schema.json, openapi.yaml — контракты между модулями
backend/
  core/      Java ↔ граф: парсинг (JavaParser), обзор и блок-схема, операции над AST, печать кода
  analysis/  правила дефектов, метрики и Big O, авто-исправления, декомпозиция
  runner/    песочница: javac + java в изолированном контейнере (run, run-batch)
  api/       REST API (Spring Boot): проекты и версии, verify, quality gate, export
frontend/    React + React Flow + elkjs + Monaco
corpus/      тестовые монолиты, входы, bench-входы
docs/        архитектура, поддержанный scope, ограничения
```

План работ: [PLAN.md](PLAN.md). Статус: [STATUS.md](STATUS.md). Решения и риски: [LEAD.md](LEAD.md).
