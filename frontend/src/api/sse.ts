/** Разбор SSE-консоли POST /run/stream: события `event:` / `data:` (contracts/openapi.yaml). */
import type { RunStreamEvent } from './types';

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
