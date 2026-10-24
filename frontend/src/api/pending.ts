/**
 * Типы эндпоинтов из ещё не слитых PR роли 4 (contracts/openapi.yaml в ветках):
 * POST /advice (#40), POST /complexity (#42), POST /run/stream (#56).
 * Лежат отдельно от types.ts, чтобы не конфликтовать с этими PR; после их слияния импорты переедут в types.ts,
 * а файл удалится. Фронт работает и без этих эндпоинтов: недоступный эндпоинт просто скрывает функцию.
 */
import type { Problem } from './types';

/** POST /projects/{id}/advice → Advice (204 — советчик выключен, ключа нет) */
export interface Advice {
  findingId: string;
  /** Объяснение и исправленный фрагмент (markdown) */
  suggestion: string;
  model?: string | null;
}

/** POST /projects/{id}/complexity → ComplexityResult */
export interface ComplexityResult {
  version: number;
  current: string;
}

/** POST /projects/{id}/run/stream — события text/event-stream (поле data — JSON) */
export type RunStreamEvent =
  | { event: 'compile'; data: { ok: boolean; problems?: Problem[]; output?: string } }
  | { event: 'stdout' | 'stderr'; data: { text: string } }
  | { event: 'exit'; data: { exitCode: number | null; timeMs: number; timedOut: boolean } }
  | { event: 'error'; data: { message: string } };

const KNOWN = new Set(['compile', 'stdout', 'stderr', 'exit', 'error']);

/**
 * Разбор потока SSE по кускам: возвращает готовые события и хвост, который надо склеить со следующим куском.
 * Событие — блок строк `event:` / `data:` до пустой строки; несколько `data:` склеиваются через \n (спецификация SSE).
 * Неизвестные события и битый JSON пропускаются — консоль не должна падать из-за одного куска.
 */
export function parseSse(buffer: string): { events: RunStreamEvent[]; rest: string } {
  const text = buffer.replace(/\r\n?/g, '\n');
  const blocks = text.split('\n\n');
  const rest = blocks.pop() ?? '';
  const events: RunStreamEvent[] = [];
  for (const block of blocks) {
    let name = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      let value = colon < 0 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') name = value;
      else if (field === 'data') data.push(value);
    }
    if (!KNOWN.has(name) || data.length === 0) continue;
    try {
      events.push({ event: name, data: JSON.parse(data.join('\n')) } as RunStreamEvent);
    } catch {
      /* битый кусок — пропускаем */
    }
  }
  return { events, rest };
}
