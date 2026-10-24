import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { RunResult, VerifyCase } from '../api/types';
import { caseMismatch, firstDiff, growthExponent, growthLabel, speedup, verdict } from './verify.ts';

const run = (stdout: string, extra: Partial<RunResult> = {}): RunResult => ({ compile: { ok: true }, stdout, stderr: '', exitCode: 0, timedOut: false, ...extra });

test('firstDiff: первая расходящаяся строка, \\r\\n и хвостовой перевод строки не считаются', () => {
  assert.equal(firstDiff('a\nb\n', 'a\r\nb'), null);
  assert.deepEqual(firstDiff('a\nb\nc', 'a\nX\nc'), { line: 2, expected: 'b', actual: 'X' });
  assert.deepEqual(firstDiff('a', 'a\nlast'), { line: 2, expected: null, actual: 'last' });
});

test('caseMismatch: канал расхождения', () => {
  const base: VerifyCase = { name: 'x', ok: false, expected: run('1\n2'), actual: run('1\n3') };
  assert.deepEqual(caseMismatch(base), { channel: 'stdout', diff: { line: 2, expected: '2', actual: '3' } });
  assert.equal(caseMismatch({ ...base, ok: true }), null);
  assert.equal(caseMismatch({ ...base, actual: run('1\n2', { exitCode: 1 }) })!.channel, 'exitCode');
  assert.equal(caseMismatch({ ...base, actual: run('', { stderr: 'java.lang.NullPointerException' }) })!.channel, 'stdout');
  assert.equal(caseMismatch({ ...base, actual: run('1\n2', { stderr: 'boom' }) })!.channel, 'stderr');
  assert.equal(caseMismatch({ ...base, actual: run('', { timedOut: true }) })!.channel, 'timeout');
  assert.equal(caseMismatch({ ...base, actual: { compile: { ok: false }, timedOut: false } })!.channel, 'compile');
});

const quadToLinear = [
  { n: 1000, baselineMs: 40, currentMs: 8 },
  { n: 2000, baselineMs: 160, currentMs: 16 },
  { n: 4000, baselineMs: 640, currentMs: 32 },
];

test('speedup — на самом большом n', () => {
  assert.deepEqual(speedup(quadToLinear), { n: 4000, ratio: 20 });
  assert.equal(speedup([]), null);
});

test('growthExponent: квадратичный эталон и линейная текущая версия', () => {
  assert.equal(growthExponent(quadToLinear, 'baselineMs'), 2);
  assert.equal(growthExponent(quadToLinear, 'currentMs'), 1);
  assert.equal(growthLabel(2), '≈ O(n²)');
  assert.equal(growthLabel(1), '≈ O(n)');
  assert.equal(growthExponent([quadToLinear[0]], 'currentMs'), null);
  assert.equal(growthLabel(null), null);
});

test('growthExponent: постоянная часть замера (старт JVM) не искажает рост', () => {
  const withJvm = quadToLinear.map((p) => ({ n: p.n, baselineMs: p.baselineMs + 300, currentMs: p.currentMs + 300 }));
  assert.equal(growthExponent(withJvm, 'baselineMs'), 2);
  assert.equal(growthExponent(withJvm, 'currentMs'), 1);
  // настоящий замер corpus/06-demo-warehouse (v1 против трёх исправлений, время с запуском JVM)
  const wh = [
    { n: 5000, baselineMs: 424, currentMs: 268 },
    { n: 10000, baselineMs: 943, currentMs: 375 },
    { n: 20000, baselineMs: 2656, currentMs: 491 },
  ];
  assert.equal(growthLabel(growthExponent(wh, 'baselineMs')), '≈ n^1.7');
  assert.equal(growthLabel(growthExponent(wh, 'currentMs')), '≈ O(n)');
  // плоский шум — не «быстрее линейного»
  assert.equal(growthExponent([{ n: 1, baselineMs: 300, currentMs: 300 }, { n: 2, baselineMs: 290, currentMs: 300 }, { n: 4, baselineMs: 310, currentMs: 300 }], 'currentMs'), 1);
  assert.equal(growthLabel(1.1), '≈ O(n)');
  assert.equal(growthLabel(2.9), '≈ O(n³)');
  assert.equal(growthLabel(1.5), '≈ n^1.5');
});

test('verdict', () => {
  assert.equal(verdict({ behaviorPreserved: true, passed: 3, total: 3, cases: [] }), 'preserved');
  assert.equal(verdict({ behaviorPreserved: false, passed: 2, total: 3, cases: [] }), 'changed');
  assert.equal(verdict({ compiled: false, behaviorPreserved: false, passed: 0, total: 3, cases: [] }), 'not-compiled');
  assert.equal(verdict({ behaviorPreserved: true, passed: 0, total: 0, cases: [] }), 'empty');
});

test('caseMismatch по ответу настоящего VerifyService: канал из префикса mismatch, compile без actual', () => {
  const exp = run('1\n2');
  assert.deepEqual(
    caseMismatch({ name: 'corpus: 01.txt', ok: false, expected: exp, actual: run('1\n3'), mismatch: 'stdout: строка 2: «2» → «3»' }),
    { channel: 'stdout', diff: { line: 2, expected: '2', actual: '3' } },
  );
  assert.equal(caseMismatch({ name: 'x', ok: false, expected: exp, actual: undefined, mismatch: 'compile: ошибка компиляции' })!.channel, 'compile');
  assert.equal(caseMismatch({ name: 'x', ok: false, expected: exp, actual: run('1\n2', { exitCode: 3 }), mismatch: 'exitCode: ожидался 0, получен 3' })!.channel, 'exitCode');
  // несколько причин — первая решает
  assert.equal(caseMismatch({ name: 'x', ok: false, expected: exp, actual: run('9', { stderr: 'e' }), mismatch: 'stdout: …; stderr: …' })!.channel, 'stdout');
  assert.equal(caseMismatch({ name: 'x', ok: false, expected: exp, actual: run('', { timedOut: true }), mismatch: 'timeout: текущая версия не уложилась в лимит времени' })!.channel, 'timeout');
});

import { endLabels } from './verify.ts';

test('endLabels: далёкие концы — как есть, близкие — разведены, совпавшие — одна подпись', () => {
  assert.deepEqual(endLabels(100, 40), { merged: false, before: 100, after: 40 });
  assert.deepEqual(endLabels(100, 94), { merged: false, before: 105, after: 89 });
  assert.deepEqual(endLabels(50, 60), { merged: false, before: 47, after: 63 });
  assert.deepEqual(endLabels(80, 81), { merged: true, y: 80.5 });
});
