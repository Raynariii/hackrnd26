import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyRunEvent, runFinished, STREAM_START } from './run.ts';

test('события потока собираются в RunResult по порядку', () => {
  let r = STREAM_START;
  assert.equal(runFinished(r), false);
  r = applyRunEvent(r, { event: 'compile', data: { ok: true } });
  r = applyRunEvent(r, { event: 'stdout', data: { text: 'a\n' } });
  r = applyRunEvent(r, { event: 'stderr', data: { text: 'warn\n' } });
  r = applyRunEvent(r, { event: 'stdout', data: { text: 'b\n' } });
  assert.equal(runFinished(r), false);
  r = applyRunEvent(r, { event: 'exit', data: { exitCode: 0, timeMs: 12, timedOut: false } });
  assert.deepEqual(r, { compile: { ok: true }, stdout: 'a\nb\n', stderr: 'warn\n', timedOut: false, exitCode: 0, timeMs: 12 });
  assert.equal(runFinished(r), true);
});

test('ошибка компиляции завершает запуск; error песочницы — в stderr', () => {
  const failed = applyRunEvent(STREAM_START, { event: 'compile', data: { ok: false, output: 'X.java:1: error' } });
  assert.equal(runFinished(failed), true);
  const err = applyRunEvent(applyRunEvent(STREAM_START, { event: 'stdout', data: { text: 'x' } }), { event: 'error', data: { message: 'песочница недоступна' } });
  assert.equal(err.stderr, 'песочница недоступна');
  assert.equal(err.stdout, 'x');
  assert.equal(runFinished(err), true);
});
