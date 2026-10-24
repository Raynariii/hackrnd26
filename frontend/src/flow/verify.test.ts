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

test('verdict', () => {
  assert.equal(verdict({ behaviorPreserved: true, passed: 3, total: 3, cases: [] }), 'preserved');
  assert.equal(verdict({ behaviorPreserved: false, passed: 2, total: 3, cases: [] }), 'changed');
  assert.equal(verdict({ compiled: false, behaviorPreserved: false, passed: 0, total: 3, cases: [] }), 'not-compiled');
  assert.equal(verdict({ behaviorPreserved: true, passed: 0, total: 0, cases: [] }), 'empty');
});
