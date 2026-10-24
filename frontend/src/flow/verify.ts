// Чистая логика экрана проверки (VerifyReport из contracts/openapi.yaml). Без React — тестируется в node.
import type { RunResult, VerifyCase, VerifyReport } from '../api/types';

export interface LineDiff {
  /** Номер первой расходящейся строки, с 1. */
  line: number;
  expected: string | null;
  actual: string | null;
}

const lines = (s: string | undefined | null) => (s ?? '').replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n');

/** Первая строка, где ожидаемый и фактический вывод разошлись; null — совпадают. */
export function firstDiff(expected: string | undefined | null, actual: string | undefined | null): LineDiff | null {
  const a = lines(expected);
  const b = lines(actual);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if (a[i] !== b[i]) return { line: i + 1, expected: a[i] ?? null, actual: b[i] ?? null };
  }
  return null;
}

export type Channel = 'stdout' | 'stderr' | 'exitCode' | 'compile' | 'timeout';

export interface CaseMismatch {
  channel: Channel;
  diff: LineDiff | null;
}

const CHANNELS: Channel[] = ['stdout', 'stderr', 'exitCode', 'compile', 'timeout'];

/**
 * Что разошлось в тест-кейсе — для подсветки. Сервер (VerifyService) уже нормализовал вывод и пишет в mismatch
 * причины через «; » с префиксом канала: «stdout: …», «exitCode: ожидался 0, получен 1», «compile: …».
 * Канал берём оттуда; номер строки считаем сами по присланным выводам. Если текущая версия не
 * компилируется, у кейса нет actual.
 */
export function caseMismatch(c: VerifyCase): CaseMismatch | null {
  if (c.ok) return null;
  const e: RunResult | undefined = c.expected;
  const a: RunResult | undefined = c.actual;
  const prefix = c.mismatch?.split(':', 1)[0].trim() as Channel | undefined;
  const fromServer = prefix && CHANNELS.includes(prefix) ? prefix : null;
  const diffOf = (ch: Channel) =>
    ch === 'stdout' ? firstDiff(e?.stdout, a?.stdout) : ch === 'stderr' ? firstDiff(e?.stderr, a?.stderr) : null;

  if (fromServer === 'compile' || (a && !a.compile.ok) || (!a && !fromServer)) return { channel: 'compile', diff: null };
  if (fromServer) return { channel: fromServer, diff: a ? diffOf(fromServer) : null };
  if (a?.timedOut && !e?.timedOut) return { channel: 'timeout', diff: null };
  const out = diffOf('stdout');
  if (out) return { channel: 'stdout', diff: out };
  const err = diffOf('stderr');
  if (err) return { channel: 'stderr', diff: err };
  if ((e?.exitCode ?? null) !== (a?.exitCode ?? null)) return { channel: 'exitCode', diff: null };
  // Сервер сказал «не совпало», а в присланных полях разницы не видно — покажем его текст mismatch.
  return { channel: 'stdout', diff: null };
}

export type BenchPoint = NonNullable<VerifyReport['benchmark']>[number];

/** Ускорение на самом большом n: во сколько раз текущая версия быстрее эталона (<1 — медленнее). */
export function speedup(points: BenchPoint[]): { n: number; ratio: number } | null {
  const sorted = [...points].filter((p) => p.currentMs > 0 && p.baselineMs > 0).sort((x, y) => x.n - y.n);
  const last = sorted.at(-1);
  return last ? { n: last.n, ratio: last.baselineMs / last.currentMs } : null;
}

/**
 * Эмпирическая степень роста k в модели «время = c + a·n^k».
 * Постоянная часть c (старт JVM, компиляция класса, чтение маленьких входов) у runner — сотни миллисекунд и на
 * коротких n съедает рост: наклон log(время)/log(n) по крайним точкам показал бы квадратичный код как ≈ O(n), а
 * линейный — как ≈ O(√n). Поэтому при трёх и более точках на геометрической сетке (n, 2n, 4n — так растут
 * bench-входы) берём отношение последних разностей: c сокращается, (t₃ − t₂)/(t₂ − t₁) = r^k.
 * Меньше 1 не бывает: вход длины n программа читает целиком — шум на плоском графике показываем как O(n).
 */
export function growthExponent(points: BenchPoint[], pick: 'baselineMs' | 'currentMs'): number | null {
  const sorted = [...points].filter((p) => p[pick] > 0 && p.n > 0).sort((x, y) => x.n - y.n);
  if (sorted.length < 2) return null;
  const first = sorted[0];
  const last = sorted.at(-1)!;
  if (first.n === last.n) return null;
  if (sorted.length >= 3) {
    const [a, b, c] = sorted.slice(-3);
    const r1 = b.n / a.n;
    const r2 = c.n / b.n;
    if (r1 > 1 && Math.abs(r1 - r2) / r1 < 0.05) {
      const d1 = b[pick] - a[pick];
      const d2 = c[pick] - b[pick];
      if (d1 <= 0 || d2 <= 0) return 1; // время не растёт сверх шума
      return Math.max(1, Math.log(d2 / d1) / Math.log(r2));
    }
  }
  return Math.max(1, Math.log(last[pick] / first[pick]) / Math.log(last.n / first.n));
}

/** Подпись роста: целые степени — классом O(…), промежуточные — числом (смесь линейной и квадратичной частей). */
export function growthLabel(k: number | null): string | null {
  if (k === null || !Number.isFinite(k)) return null;
  if (k < 1.25) return '≈ O(n)';
  if (Math.abs(k - 2) <= 0.25) return '≈ O(n²)';
  if (Math.abs(k - 3) <= 0.25) return '≈ O(n³)';
  if (k > 3.25) return '≈ O(n³) и хуже';
  return `≈ n^${k.toFixed(1)}`;
}

export type Verdict = 'preserved' | 'changed' | 'not-compiled' | 'empty';

export function verdict(r: VerifyReport): Verdict {
  if (r.compiled === false) return 'not-compiled';
  if (r.total === 0) return 'empty';
  return r.behaviorPreserved ? 'preserved' : 'changed';
}
