import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseSse } from './pending.ts';

test('parseSse: события по порядку, хвост без пустой строки остаётся', () => {
  const { events, rest } = parseSse(
    'event: compile\ndata: {"ok":true}\n\nevent: stdout\ndata: {"text":"a\\n"}\n\nevent: exit\ndata: {"exitCode":0',
  );
  assert.deepEqual(events, [
    { event: 'compile', data: { ok: true } },
    { event: 'stdout', data: { text: 'a\n' } },
  ]);
  assert.equal(rest, 'event: exit\ndata: {"exitCode":0');
});

test('parseSse: кусок, разрезанный посередине, собирается со следующим', () => {
  const first = parseSse('event: stdout\ndata: {"te');
  assert.deepEqual(first.events, []);
  const second = parseSse(`${first.rest}xt":"hi"}\n\n`);
  assert.deepEqual(second.events, [{ event: 'stdout', data: { text: 'hi' } }]);
  assert.equal(second.rest, '');
});

test('parseSse: CRLF, комментарии, data без пробела, несколько data', () => {
  const { events } = parseSse(':ping\r\nevent:stderr\r\ndata:{"text":\r\ndata:"x"}\r\n\r\n');
  assert.deepEqual(events, [{ event: 'stderr', data: { text: 'x' } }]);
});

test('parseSse: неизвестные события и битый JSON пропускаются', () => {
  const { events } = parseSse('event: ping\ndata: {}\n\nevent: stdout\ndata: {oops\n\nevent: exit\ndata: {"exitCode":1,"timeMs":5,"timedOut":false}\n\n');
  assert.deepEqual(events, [{ event: 'exit', data: { exitCode: 1, timeMs: 5, timedOut: false } }]);
});

test('isUnsupported: «нет эндпоинта» отличается от «нет проекта/находки»', async () => {
  const { ApiRequestError, isUnsupported } = await import('./errors.ts');
  assert.equal(isUnsupported(new ApiRequestError(404, { error: 'Not Found', message: '' })), true);
  assert.equal(isUnsupported(new ApiRequestError(405, { error: 'Method Not Allowed', message: '' })), true);
  assert.equal(isUnsupported(new ApiRequestError(501, { error: 'not_implemented', message: '' })), true);
  assert.equal(isUnsupported(new ApiRequestError(404, { error: 'not_found', message: 'Проект не найден' })), false);
  assert.equal(isUnsupported(new ApiRequestError(422, { error: 'unsupported_op', message: '' })), false);
  assert.equal(isUnsupported(new Error('x')), false);
});
