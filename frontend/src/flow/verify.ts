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
 * Эмпирическая степень роста: наклон log(время) от log(n) по крайним точкам.
 * ≈1 — линейно, ≈2 — квадратично. Нужно минимум две точки с разными n; на коротких временах шумно.
 */
export function growthExponent(points: BenchPoint[], pick: 'baselineMs' | 'currentMs'): number | null {
  const sorted = [...points].filter((p) => p[pick] > 0 && p.n > 0).sort((x, y) => x.n - y.n);
  if (sorted.length < 2) return null;
  const a = sorted[0];
  const b = sorted.at(-1)!;
  if (a.n === b.n) return null;
  return Math.log(b[pick] / a[pick]) / Math.log(b.n / a.n);
}

/** Подпись класса роста по степени: ближайший из O(1), O(log n)… грубо, только для подсказки. */
export function growthLabel(k: number | null): string | null {
  if (k === null || !Number.isFinite(k)) return null;
  if (k < 0.3) return '≈ O(1)';
  if (k < 0.75) return '≈ O(√n)';
  if (k < 1.35) return '≈ O(n)';
  if (k < 1.75) return '≈ O(n log n)…O(n^1.5)';
  if (k < 2.5) return '≈ O(n²)';
  return '≈ O(n³) и хуже';
}

export type Verdict = 'preserved' | 'changed' | 'not-compiled' | 'empty';

export function verdict(r: VerifyReport): Verdict {
  if (r.compiled === false) return 'not-compiled';
  if (r.total === 0) return 'empty';
  return r.behaviorPreserved ? 'preserved' : 'changed';
}
