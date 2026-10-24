// Логика правки на настоящих снимках ядра: операции выполнены DefaultGraphOperations (main), см. mocks/real/MainOps.json.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Graph, GraphNode } from '../api/types';
import mainOps from '../mocks/real/MainOps.json' with { type: 'json' };
import { allowedOps, changedLineNumbers, findMethodAgain, firstChangedLine } from './edit.ts';
import { nodeAtLine } from './sourceMap.ts';

interface Snap { source: string; overview: Graph; flow: Graph }
const demo = mainOps as unknown as Snap & { file: string; ops: Record<string, { request: { op: string; nodeId: string }; after: Snap }> };
const files = (source: string) => [{ path: demo.file, content: source }];
const node = (kind: GraphNode['kind']): GraphNode => ({ id: 'x', kind, label: kind });

test('allowedOps: start/end, finally, контейнеры, обзор', () => {
  assert.deepEqual([...allowedOps(node('start'), 'method')], ['insertAfter']);
  assert.deepEqual([...allowedOps(node('end'), 'method')], []);
  assert.deepEqual([...allowedOps(node('finally'), 'method')].sort(), ['delete', 'insertAfter']);
  assert.deepEqual([...allowedOps(node('condition'), 'method')].sort(), ['delete', 'editNode', 'insertAfter']);
  assert.deepEqual([...allowedOps(node('method'), 'overview')].sort(), ['delete', 'editNode']);
  assert.deepEqual([...allowedOps(node('class'), 'overview')], []);
});

test('после каждой настоящей операции метод находится заново, а изменённая строка — это изменённый узел', () => {
  const methodId = demo.flow.scope!;
  const expectKind: Record<string, GraphNode['kind'] | null> = {
    editCondition: 'condition',
    insertVariable: 'variable',
    insertCondition: 'condition',
    editLoopAsShown: 'loop',
    deleteCall: null, // удалённого узла нет — выделение не ставим
  };
  for (const [name, rec] of Object.entries(demo.ops)) {
    const again = findMethodAgain(demo.overview, methodId, rec.after.overview);
    assert.equal(again, rec.after.flow.scope, name);
    const changed = firstChangedLine(files(demo.source), files(rec.after.source))!;
    assert.ok(changed, name);
    if (expectKind[name]) assert.equal(nodeAtLine(rec.after.flow, changed.file, changed.line)?.kind, expectKind[name], name);
  }
});

test('findMethodAgain: сигнатуру поменяли — находим по имени; имени нет — null', () => {
  const ov = demo.overview;
  const m = ov.nodes.find((n) => n.kind === 'method' && n.label === 'processOrders')!;
  const renamedSig: Graph = { ...ov, nodes: ov.nodes.map((n) => (n.id === m.id ? { ...n, id: 'gZ', code: 'static List<Integer> processOrders(List<Integer> xs)' } : n)) };
  assert.equal(findMethodAgain(ov, m.id, renamedSig), 'gZ');
  const gone: Graph = { ...ov, nodes: ov.nodes.filter((n) => n.id !== m.id) };
  assert.equal(findMethodAgain(ov, m.id, gone), null);
});

test('firstChangedLine: без изменений — null', () => {
  assert.equal(firstChangedLine(files('a\nb'), files('a\nb')), null);
  assert.deepEqual(firstChangedLine(files('a\nb'), files('a\nc')), { file: demo.file, line: 2 });
});

test('changedLineNumbers: вставка, замена, правки в двух местах, удаление', () => {
  assert.deepEqual(changedLineNumbers('a\nb\nc', 'a\nX\nb\nc'), [2]);
  assert.deepEqual(changedLineNumbers('a\nb\nc', 'a\nB\nc'), [2]);
  assert.deepEqual(changedLineNumbers('a\nb\nc\nd\ne', 'K\na\nb\nc\nD\ne'), [1, 5]);
  assert.deepEqual(changedLineNumbers('a\nb\nc', 'a\nc'), []);
  assert.deepEqual(changedLineNumbers('a', 'a'), []);
});
