# Java Blueprints — визуальная среда программирования

Веб-инструмент, который разбирает Java-монолит в граф, позволяет исследовать и править программу как код и как
блок-схему, находит дефекты и оценивает сложность (Big O), улучшает код и **автоматически проверяет, что поведение
программы сохранилось**.

Кейс Axenix, хакатон 2026.

## Быстрый запуск (Docker)

Нужен Docker с Compose v2.

```bash
cp .env.example .env
docker compose up --build
```

Откройте http://localhost:3000 → «Импорт Java» → выберите, например, `corpus/00-hello/Main.java`.

| Сервис | Порт | Назначение |
|---|---|---|
| web | 3000 | интерфейс (nginx), проксирует `/api` |
| api | 8080 | REST API (`contracts/openapi.yaml`) |
| runner | — | песочница компиляции и запуска, доступна только из `api`, без выхода в сеть |

## Запуск для разработки

Требуется Java 21+, Maven 3.9+, Node 22+.

```bash
cd backend && mvn -B verify                         # сборка и тесты
cd backend && mvn -B -pl runner -am spring-boot:run # песочница :8090
cd backend && mvn -B -pl api -am spring-boot:run    # API :8080
cd frontend && npm ci && npm run dev                # UI :5173
```

Фронт без бэкенда: `cd frontend && npm run dev:mock`.

## Структура

```
contracts/   graph.schema.json, openapi.yaml — контракты между модулями
backend/
  core/      Java ↔ граф: парсинг, построение графа, операции над AST, генерация кода
  analysis/  правила дефектов, Big O, авто-исправления, декомпозиция
  runner/    песочница: javac + java в изолированном контейнере
  api/       REST API (Spring Boot)
frontend/    React + React Flow + Monaco
corpus/      тестовые монолиты
docs/        архитектура, поддержанный scope, ограничения
```

Документация: [docs/architecture.md](docs/architecture.md). План работ: [PLAN.md](PLAN.md). Статус: [STATUS.md](STATUS.md).
