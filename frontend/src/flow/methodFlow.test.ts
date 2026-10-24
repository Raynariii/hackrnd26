// Запуск: npm test (node --test, без зависимостей). Файл исключён из tsc-сборки.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Graph } from '../api/types';
import { buildFlowModel, frameTitle, nodeShape, nodeSize, collectBoxes, commonContainer, frameId, toElkGraph, type ElkNodeLike } from './methodFlow.ts';

const r = (line: number) => ({ file: 'Main.java', startLine: line, endLine: line });

/** Как mockMethodFlow: цикл с условием внутри, «Нет» условия ведёт обратно к циклу. */
const loopFlow: Graph = {
  level: 'method',
  scope: 'gm1',
  nodes: [
    { id: 'n0', kind: 'start', label: 'Метод', range: r(4) },
    { id: 'n2', kind: 'condition', label: 'Условие', code: 'orders == null', range: r(6) },
    { id: 'n3', kind: 'return', label: 'Возврат', range: r(7) },
    { id: 'n4', kind: 'loop', label: 'Цикл (for each)', range: r(9) },
    { id: 'n5', kind: 'condition', label: 'Условие', parent: 'n4', range: r(10) },
    { id: 'n6', kind: 'call', label: 'Операция', parent: 'n4', range: r(11) },
    { id: 'n7', kind: 'return', label: 'Возврат', range: r(14) },
  ],
  edges: [
    { id: 'e1', from: 'n0', to: 'n2', kind: 'next' },
    { id: 'e2', from: 'n2', to: 'n3', kind: 'true', label: 'Да' },
    { id: 'e3', from: 'n2', to: 'n4', kind: 'false' },
    { id: 'e4', from: 'n4', to: 'n5', kind: 'loopBody' },
    { id: 'e5', from: 'n5', to: 'n6', kind: 'true' },
    { id: 'e6', from: 'n6', to: 'n4', kind: 'loopBack' },
    { id: 'e7', from: 'n5', to: 'n4', kind: 'false', label: 'Нет' },
    { id: 'e8', from: 'n4', to: 'n7', kind: 'loopExit' },
  ],
};

/** Вложенный цикл внутри try + catch с телом. */
const nestedFlow: Graph = {
  level: 'method',
  scope: 'gm2',
  nodes: [
    { id: 's', kind: 'start', label: 'Метод' },
    { id: 't', kind: 'try', label: 'try' },
    { id: 'outer', kind: 'loop', label: 'Цикл', parent: 't' },
    { id: 'inner', kind: 'loop', label: 'Цикл', parent: 'outer' },
    { id: 'op', kind: 'operation', label: 'Операция', parent: 'inner' },
    { id: 'c', kind: 'catch', label: 'catch (Exception e)', parent: 't' },
    { id: 'log', kind: 'call', label: 'Вызов', parent: 'c' },
    { id: 'e', kind: 'end', label: 'Конец' },
  ],
  edges: [
    { id: 'a', from: 's', to: 't', kind: 'next' },
    { id: 'b', from: 't', to: 'outer', kind: 'next' },
    { id: 'c1', from: 'outer', to: 'inner', kind: 'loopBody' },
    { id: 'd', from: 'inner', to: 'op', kind: 'loopBody' },
    { id: 'f', from: 'op', to: 'inner', kind: 'loopBack' },
    { id: 'g', from: 'inner', to: 'outer', kind: 'loopExit' },
    { id: 'h', from: 't', to: 'c', kind: 'exception', label: 'IOException' },
    { id: 'i', from: 'c', to: 'log', kind: 'next' },
    { id: 'j', from: 'outer', to: 'e', kind: 'loopExit' },
    { id: 'k', from: 'log', to: 'e', kind: 'next' },
    { id: 'dangling', from: 'log', to: 'nowhere', kind: 'next' },
  ],
};

test('контейнер получает рамку, заголовок и тело лежат внутри неё', () => {
  const m = buildFlowModel(loopFlow, { grouping: true });
  const frames = m.items.filter((i) => i.type === 'frame');
  assert.deepEqual(frames.map((f) => f.id), [frameId('n4')]);
  assert.equal(m.parentOf.get('n4'), frameId('n4'));
  assert.equal(m.parentOf.get('n5'), frameId('n4'));
  assert.equal(m.parentOf.get('n6'), frameId('n4'));
  assert.equal(m.parentOf.get('n2'), null);
  assert.equal(m.parentOf.get(frameId('n4')), null);
});

test('рамки идут раньше блоков и родительские раньше вложенных', () => {
  const m = buildFlowModel(nestedFlow, { grouping: true });
  const order = m.items.map((i) => i.id);
  const firstBlock = m.items.findIndex((i) => i.type === 'block');
  assert.ok(m.items.slice(0, firstBlock).every((i) => i.type === 'frame'));
  assert.ok(order.indexOf(frameId('t')) < order.indexOf(frameId('outer')));
  assert.ok(order.indexOf(frameId('outer')) < order.indexOf(frameId('inner')));
  assert.equal(m.parentOf.get(frameId('inner')), frameId('outer'));
  assert.equal(m.parentOf.get(frameId('c')), frameId('t'));
  assert.equal(m.parentOf.get('log'), frameId('c'));
  assert.equal(m.parentOf.get('op'), frameId('inner'));
});

test('подписи Да/Нет по умолчанию и ручка «Нет» сбоку у условия', () => {
  const m = buildFlowModel(loopFlow, { grouping: true });
  const e3 = m.edges.find((e) => e.edge.id === 'e3')!;
  assert.equal(e3.label, 'Нет');
  assert.equal(e3.tone, 'no');
  assert.equal(e3.sourceHandle, 'no');
  const e5 = m.edges.find((e) => e.edge.id === 'e5')!;
  assert.equal(e5.label, 'Да');
  assert.equal(e5.tone, 'yes');
  assert.equal(e5.sourceHandle, 'out');
});

test('выход из цикла идёт с левой ручки заголовка, вход в тело — снизу', () => {
  const m = buildFlowModel(loopFlow, { grouping: true });
  assert.equal(m.edges.find((e) => e.edge.id === 'e8')!.sourceHandle, 'exit');
  assert.equal(m.edges.find((e) => e.edge.id === 'e4')!.sourceHandle, 'out');
});

test('обратные рёбра: loopBack и переход изнутри тела к заголовку своего цикла', () => {
  const m = buildFlowModel(loopFlow, { grouping: true });
  const back = m.edges.filter((e) => e.back).map((e) => e.edge.id).sort();
  assert.deepEqual(back, ['e6', 'e7']);
  const e7 = m.edges.find((e) => e.edge.id === 'e7')!;
  assert.equal(e7.label, 'Нет');
  assert.equal(e7.sourceHandle, 'back-out');
  assert.equal(e7.targetHandle, 'back-in');
  // выход вложенного цикла во внешний — тоже возврат к заголовку контейнера
  const nested = buildFlowModel(nestedFlow, { grouping: true });
  assert.ok(nested.edges.find((e) => e.edge.id === 'g')!.back);
  assert.ok(!nested.edges.find((e) => e.edge.id === 'c1')!.back);
});

test('рёбра к несуществующим узлам отбрасываются', () => {
  const m = buildFlowModel(nestedFlow, { grouping: true });
  assert.equal(m.edges.find((e) => e.edge.id === 'dangling'), undefined);
});

test('без группировки (обзор) рамок нет', () => {
  const m = buildFlowModel(nestedFlow, { grouping: false });
  assert.equal(m.items.filter((i) => i.type === 'frame').length, 0);
  assert.ok(m.items.every((i) => i.parent === null));
});

test('циклы в parent не роняют построение', () => {
  const g: Graph = {
    level: 'method',
    nodes: [
      { id: 'a', kind: 'loop', label: 'a', parent: 'b' },
      { id: 'b', kind: 'loop', label: 'b', parent: 'a' },
      { id: 'self', kind: 'operation', label: 's', parent: 'self' },
    ],
    edges: [],
  };
  const m = buildFlowModel(g, { grouping: true });
  assert.equal(m.items.filter((i) => i.type === 'block').length, 3);
  assert.equal(m.parentOf.get('self'), null);
});

test('elk: ребро кладётся в ближайший общий контейнер, обратные не попадают', () => {
  const m = buildFlowModel(nestedFlow, { grouping: true });
  assert.equal(commonContainer(m.parentOf, 'inner', 'op'), frameId('inner'));
  assert.equal(commonContainer(m.parentOf, 'outer', 'e'), 'root');
  assert.equal(commonContainer(m.parentOf, 't', 'c'), frameId('t'));

  const root = toElkGraph(m);
  const find = (n: ElkNodeLike, id: string): ElkNodeLike | undefined =>
    n.id === id ? n : (n.children ?? []).map((c) => find(c, id)).find(Boolean);
  const edgeIds = (id: string) => (find(root, id)!.edges ?? []).map((e) => e.id).sort();

  assert.deepEqual(edgeIds(frameId('inner')), ['d']);
  assert.deepEqual(edgeIds(frameId('t')), ['b', 'h']);
  assert.ok(!JSON.stringify(root).includes('"f"'), 'loopBack исключён из раскладки');
  assert.equal(find(root, 'op')!.width, 260);
  assert.equal(root.layoutOptions!['elk.hierarchyHandling'], 'INCLUDE_CHILDREN');
});

test('collectBoxes отдаёт координаты относительно родителя', () => {
  const boxes = collectBoxes({
    id: 'root',
    children: [{ id: 'frame:x', x: 10, y: 20, width: 300, height: 200, children: [{ id: 'x', x: 16, y: 36, width: 260, height: 64 }] }],
  });
  assert.deepEqual(boxes.get('frame:x'), { x: 10, y: 20, width: 300, height: 200 });
  assert.deepEqual(boxes.get('x'), { x: 16, y: 36, width: 260, height: 64 });
});

test("kind 'constructor' не цепляет Object.prototype.constructor", () => {
  assert.deepEqual(nodeSize('constructor'), { width: 260, height: 64 });
  assert.equal(nodeShape('constructor'), 'process');
  assert.equal(frameTitle({ id: 'x', kind: 'constructor', label: 'Main()' }), 'Main()');
});
