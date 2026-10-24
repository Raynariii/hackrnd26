# 04-text-stats — анализ текстов (~500 строк)

Статистика символов и слов (русский и английский), частотный словарь, пары слов, палиндромы, повторы рядом,
индекс удобочитаемости, самое длинное предложение, шифр Цезаря, анаграммы. Режим задаётся первой строкой `#mode`.

**Scope:** `String`/`StringBuilder` (`reverse`, `setLength`), `Character` (`isLetterOrDigit`, `toLowerCase`, …),
char-арифметика по кириллице и латинице, `StandardCharsets.UTF_8`, `Scanner`, `printf` с шириной полей,
`switch` по строкам, метка `outer:` и `continue outer`, массивы-счётчики, `boolean[]`, `Arrays.sort(char[])`,
`HashMap`, `TreeMap`, `TreeSet`, `Comparable`, лямбда-компаратор, `Map.merge`; проверяемое `FormatException`
и непроверяемое `TextTooLongException`.

**Заложенные дефекты:** `nested-loop-same-collection` (`printAnagrams`, O(n²) по уникальным словам),
`collection-lookup-in-loop` (`printRepeats` — список пополняется в цикле), `string-concat-in-loop` (`printBigrams`),
`deep-nesting` (`analyzeCharacters`), `magic-number` (`printReadability`).

**Входы:** `01-russian` (Пушкин + палиндромы и анаграммы), `02-english` (`#mode short`), `03-words-mode`,
`04-bad-header` (предупреждение в stderr), `05-blank`, `bench-{2000,4000,8000}` (сгенерированный текст).
