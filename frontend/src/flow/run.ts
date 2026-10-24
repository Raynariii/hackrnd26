import type { RunResult } from '../api/types';
import type { RunStreamEvent } from '../api/pending';

/** Начало потокового запуска: компиляция ещё не ответила, вывода нет; timeMs появится с событием exit. */
export const STREAM_START: RunResult = { compile: { ok: true }, stdout: '', stderr: '', timedOut: false };

/** Событие SSE-консоли → новое состояние вывода (неизменяемо: каждый кусок — новый объект для React). */
export function applyRunEvent(run: RunResult, e: RunStreamEvent): RunResult {
  switch (e.event) {
    case 'compile': return { ...run, compile: e.data };
    case 'stdout': return { ...run, stdout: (run.stdout ?? '') + e.data.text };
    case 'stderr': return { ...run, stderr: (run.stderr ?? '') + e.data.text };
    case 'exit': return { ...run, exitCode: e.data.exitCode, timeMs: e.data.timeMs, timedOut: e.data.timedOut };
    case 'error': return { ...run, stderr: `${run.stderr ?? ''}${run.stderr ? '\n' : ''}${e.data.message}`, exitCode: null, timeMs: run.timeMs ?? 0 };
  }
}

/** Запуск завершён: пришёл exit (или error), либо компиляция не прошла. */
export const runFinished = (run: RunResult) => run.timeMs !== undefined || !run.compile.ok;
