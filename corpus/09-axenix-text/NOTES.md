# 09-axenix-text — TextAnalyzerMess (монолит кейсодателя Axenix)

Исходный файл кейсодателя без изменений, 922 строки. stdin: число повторов текста (1..200, по умолчанию 20) и слово
для поиска (по умолчанию `the`).

**Программа сама завершается исключением** (код 1): `verifyCaesarForAllWords` → `caesarDecode(…, 3)` → `caesar(…, 26)`
→ `IllegalArgumentException`. Падает детерминированно на любом входе. Это проверка, что verify сравнивает
stderr (без кадров стека) и код выхода, а не только stdout. Файл не правим: это код кейсодателя.

Входы: `01-default`, `02-quick`, `03-empty`, `04-zero`, `05-not-number`; `bench-25/50/100` — число повторов
(100 → ~2 с; 200 → ~8 с, близко к таймауту, поэтому не берём).

Прогон конвейера (25.10): 54 метода, 40 находок (17 `string-concat-in-loop`, 13 `duplicated-code`, …). Применено
13 исправлений (gate 11/11). **12 исправлений `string-concat-in-loop` gate отклонил по делу**:
`StringConcatFix` теряет начальное значение переменной (`String s = "x"; for … s += …` → `new StringBuilder()`).
Пример: `rleDecode` печатает `ffff` вместо `aaabbbccccddddddeffff`. Запрос роли 2b — STATUS «Запросы».

После исправления `StringConcatFix` (`append(s)` в начале builder) gate не отклоняет ни одного исправления: 15 применено, verify 14/14.
