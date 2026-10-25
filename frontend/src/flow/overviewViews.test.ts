// Запуск: npm test (node --test, без зависимостей). Файл исключён из tsc-сборки.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Graph } from '../api/types';
import { scenarioGraph, sectionsGraph } from './overviewViews.ts';
import { buildFlowModel, frameTitle } from './methodFlow.ts';

const r = (line: number) => ({ file: 'Shop.java', startLine: line, endLine: line });

const overview: Graph = {
  level: 'overview',
  nodes: [
    { id: 'c', kind: 'class', label: 'Shop', range: r(1) },
    { id: 'f', kind: 'field', label: 'stock', code: 'static Map<String, Integer> stock', parent: 'c', range: r(5), meta: { section: 'Состояние' } },
    { id: 'add', kind: 'method', label: 'add', code: 'static void add()', parent: 'c', range: r(10), meta: { section: 'Склад', doc: 'Добавляет товар.' } },
    { id: 'has', kind: 'method', label: 'has', code: 'static boolean has()', parent: 'c', range: r(20), meta: { section: 'Склад' } },
    { id: 'demo', kind: 'method', label: 'demo', code: 'static void demo()', parent: 'c', range: r(30) },
    { id: 'main', kind: 'method', label: 'main', code: 'public static void main(String[] args)', parent: 'c', range: r(40), meta: { section: 'Склад', entry: true } },
  ],
  edges: [
    { id: 'k1', from: 'c', to: 'f', kind: 'contains' },
    { id: 'm1', from: 'main', to: 'add', kind: 'call', label: '×2' },
    { id: 'm2', from: 'main', to: 'has', kind: 'call' },
    { id: 'a1', from: 'add', to: 'has', kind: 'call' },
    { id: 'self', from: 'has', to: 'has', kind: 'call' },
  ],
};

test('сценарий: от main по вызовам, шаги на рёбрах, повторный вызов — вне дерева', () => {
  const { graph, cross } = scenarioGraph(overview);
  assert.deepEqual(graph.nodes.map((n) => n.id), ['main', 'add', 'has', 'demo']);
  assert.deepEqual(graph.edges.map((e) => [e.id, e.label]), [['m1', '1 · ×2'], ['m2', '2'], ['a1', '1']]);
  assert.deepEqual([...cross], ['a1']); // has уже вызван из main
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  assert.equal(byId.get('main')!.code, 'public static void main(String[] args)');
  assert.equal(byId.get('add')!.code, 'Добавляет товар.');
  assert.equal(byId.get('has')!.code, 'Склад');
  assert.equal(byId.get('demo')!.code, 'Не вызывается из main');
  assert.ok(graph.nodes.every((n) => n.parent === null && n.kind !== 'field'));

  const model = buildFlowModel(graph, { grouping: false, direction: 'RIGHT', floating: cross });
  assert.equal(model.direction, 'RIGHT');
  assert.deepEqual(model.edges.filter((e) => e.inLayout).map((e) => e.edge.id), ['m1', 'm2']);
  assert.deepEqual(model.edges.map((e) => [e.sourceHandle, e.targetHandle])[0], ['east', 'west']);
});

test('разделы: рамка раздела внутри типа, без блока-заголовка', () => {
  const g = sectionsGraph(overview);
  const model = buildFlowModel(g, { grouping: true, packed: true });
  const frames = model.items.filter((i) => i.type === 'frame').map((i) => frameTitle(i.node));
  assert.deepEqual(frames.sort(), ['Класс', 'Склад', 'Состояние']);
  assert.equal(model.parentOf.get('add'), model.parentOf.get('has'));
  assert.equal(model.parentOf.get('demo'), 'frame:c'); // без раздела — прямо в типе
  assert.ok(!model.items.some((i) => i.type === 'block' && i.node.meta?.sectionFrame));
});
