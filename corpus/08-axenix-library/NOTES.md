# 08-axenix-library — LibraryManagerMess (монолит кейсодателя Axenix)

Исходный файл кейсодателя без изменений, 782 строки. Класс `LibraryManagerMess`: runner находит `main` сам.
stdin — число прогонов сценария 1..3, по умолчанию 1. Вне диапазона, пустой ввод и не число дают 1.

Входы: `01-one`, `02-three`, `03-empty`, `04-out-of-range` (7), `05-not-number`; `bench-1..3` — число прогонов.

Прогон конвейера (25.10, api + runner локально): 50 методов, 22 находки (8 `string-concat-in-loop`,
9 `duplicated-code`, …). Применено 3 исправления (gate 10/10), декомпозиция Parser/Analyzer/Optimizer/MainPart
(gate 10/10), экспорт `10/10 true`. Фаззинг операций: 45 операций `wrapIf`/`extractMethod` подряд, после них verify 10/10.
