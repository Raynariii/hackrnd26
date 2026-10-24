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
    if (!(name in expectKind)) continue; // операции над выделением — в отдельном тесте ниже
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

// --- операции над выделением: снимки настоящего ядра (main, PR #10) ---
import { inCodeOrder, isJavaIdentifier, moveTargets, selectionOps } from './edit.ts';

type Rec = { request: { op: string; nodeIds?: string[]; targetId?: string | null }; after?: Snap; rejected?: string };
const multi = demo.ops as unknown as Record<string, Rec>;

test('selectionOps: операторы — все действия; start/catch/case — нельзя; обзор — move/delete для членов', () => {
  const stmt = demo.flow.nodes.filter((n) => ['ga22', 'ga33'].includes(n.id));
  assert.deepEqual(selectionOps(stmt, 'method'), ['wrapIf', 'wrapLoop', 'extractMethod', 'move', 'delete']);
  assert.deepEqual(selectionOps([...stmt, demo.flow.nodes[0]], 'method'), []);
  assert.deepEqual(selectionOps([node('catch')], 'method'), []);
  const methods = demo.overview.nodes.filter((n) => n.kind === 'method');
  assert.deepEqual(selectionOps(methods, 'overview'), ['move', 'delete']);
  assert.deepEqual(selectionOps([demo.overview.nodes[0]], 'overview'), []);
});

test('moveTargets: без конца метода, без выделенных и их содержимого; «в начало тела» для start', () => {
  const loop = demo.flow.nodes.find((n) => n.kind === 'loop')!;
  const ids = moveTargets(demo.flow, [loop]).map((t) => t.id);
  assert.ok(!ids.includes(loop.id) && !ids.some((id) => id.endsWith(':end')));
  assert.ok(!ids.includes('ga55') && !ids.includes('ga64'), 'содержимое цикла не цель');
  assert.equal(moveTargets(demo.flow, [loop])[0].label, 'в начало тела метода');
});

test('записанные операции над выделением: id из исходной схемы, после — метод находится, изменение попадает в блок', () => {
  for (const [name, rec] of Object.entries(multi)) {
    if (!rec.request.nodeIds) continue;
    for (const id of rec.request.nodeIds) assert.ok(demo.flow.nodes.some((n) => n.id === id), `${name}: ${id}`);
    if (rec.rejected) continue;
    const after = rec.after!;
    assert.equal(findMethodAgain(demo.overview, demo.flow.scope!, after.overview), after.flow.scope, name);
    if (name === 'deleteMany') continue;
    const hit = changedLineNumbers(demo.source, after.source).map((l) => nodeAtLine(after.flow, demo.file, l)).find(Boolean);
    assert.ok(hit, `${name}: изменённая строка → блок`);
  }
  assert.match(multi.wrapIfNotAdjacent.rejected!, /подряд/);
  assert.match(multi.extractWithReturn.rejected!, /return/);
});

test('inCodeOrder и isJavaIdentifier', () => {
  const [a, b] = [demo.flow.nodes.find((n) => n.id === 'ga47')!, demo.flow.nodes.find((n) => n.id === 'ga22')!];
  assert.deepEqual(inCodeOrder([a, b]).map((n) => n.id), ['ga22', 'ga47']);
  assert.ok(isJavaIdentifier('computeTotal') && !isJavaIdentifier('2x') && !isJavaIdentifier('a b'));
});
