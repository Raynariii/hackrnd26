# 03-bank — банк (~550 строк)

Счета в трёх валютах, пополнение, снятие с комиссией, переводы с конвертацией и откатом, капитализация процентов,
аннуитетный кредит, проверка номера карты по Луну, выписка, гистограмма, итоги, код выхода.

**Scope (акцент — разделы 1 и 2):** примитивы `byte`/`short`/`char`/`int`/`long`/`double`, побитовые флаги,
`static`-блок инициализации, `final`, массивы 1D/2D, `System.arraycopy`, `Arrays.copyOf/copyOfRange/sort/asList`,
арифметика и `%`, `Math.addExact/round/ceil/pow/abs/max/floorMod`, приведение типов, тернарный оператор,
`do-while`, метка `loop:` и `break loop`, `switch` и switch-выражения по `char`/`int`, интерфейс с `default`-методом,
`equals`/`hashCode`/`toString`, обёртки (`Integer.valueOf`, кэш `Integer`, `compare`, `toHexString`, `Long.MAX_VALUE`),
`String` (`repeat`, `chars()`, `getBytes(StandardCharsets.UTF_8)`, `join`, `format` с `Locale.ROOT` и `%,d`),
`Formatter`, `Scanner(System.in, StandardCharsets.UTF_8)`, `System.err`, `System.exit(код)`; multi-catch, `finally`,
проверяемое `InsufficientFundsException`, цепочка причин (`new IllegalStateException(msg, e)`).

**Заложенные дефекты:** `string-concat-in-loop` (`statement`), `nested-loop-same-collection` (`suspiciousPairs`),
`deep-nesting` и `long-method` (`loan`, `main`), `magic-number` (`commission`).

**Входы:** `01-basic`, `02-errors` (выход с кодом 3 по `QUIT 3`), `03-quit`, `04-unknown` (код выхода 2),
`bench-{2000,4000,8000}` (операции по 8–48 счетам).
