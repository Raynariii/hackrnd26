// Тесты на настоящих графах ядра (PR #5) и находках анализатора — src/mocks/real/*.json.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Graph } from '../api/types';
import flowDemo from '../mocks/real/Flow.json' with { type: 'json' };
import ordersDemo from '../mocks/real/OrdersMonolith.json' with { type: 'json' };
import { buildFlowModel, nodeShape, toElkGraph } from './methodFlow.ts';
import { findingsByNode, nodeAtLine, nodeForFinding } from './sourceMap.ts';
import type { RealDemo } from '../mocks/realDemos.ts';

const flow = flowDemo as unknown as RealDemo;
const orders = ordersDemo as unknown as RealDemo;
const methodByName = (d: RealDemo, name: string): Graph =>
  Object.values(d.methods).find((g) => g.nodes[0].meta?.name === name)!;

test('все настоящие блок-схемы строятся: ручки рёбер есть у фигур, рёбра в раскладке ведут к блокам', () => {
  for (const d of [flow, orders]) {
    for (const g of Object.values(d.methods)) {
      const m = buildFlowModel(g, { grouping: true });
      const kind = new Map(g.nodes.map((n) => [n.id, n.kind]));
      for (const e of m.edges) {
        const shape = nodeShape(kind.get(e.edge.from)!);
        if (e.sourceHandle === 'no') assert.equal(shape, 'decision', e.edge.id);
        if (e.sourceHandle === 'exit') assert.equal(shape, 'loop', e.edge.id);
      }
      const elk = JSON.stringify(toElkGraph(m));
      for (const e of m.edges.filter((x) => x.back)) assert.ok(!elk.includes(`"id":"${e.edge.id}"`), 'обратное ребро не в раскладке');
    }
  }
});

test('readAll: рамки для while(true), try, обоих catch и finally; continue и loopBack — обратные', () => {
  const g = methodByName(flow, 'readAll');
  const m = buildFlowModel(g, { grouping: true });
  const frameKinds = m.items.filter((i) => i.type === 'frame').map((i) => i.node.kind).sort();
  for (const k of ['catch', 'catch', 'finally', 'loop', 'try']) assert.ok(frameKinds.includes(k as never), k);
  const back = m.edges.filter((e) => e.back).map((e) => e.edge.kind).sort();
  assert.deepEqual(back, ['continue', 'loopBack']);
  assert.ok(!g.edges.some((e) => e.kind === 'loopExit'), 'у while(true) нет loopExit');
});

test('находка → блок через meta.covers: contains во вложенном цикле → условие, а не метод', () => {
  const g = methodByName(orders, 'duplicateCheck');
  const f = orders.diagnostics.findings.find((x) => x.rule === 'collection-lookup-in-loop' && x.startLine === 42)!;
  const node = nodeForFinding(g, f)!;
  assert.equal(node.kind, 'condition');
  assert.ok((node.meta!.covers as string[]).includes(f.nodeIds![0]));
  // и та же находка на обзорном графе — метод (по строке)
  assert.equal(node.range!.startLine, 42);
  assert.equal(nodeForFinding(orders.overview, f)!.label, 'duplicateCheck');
});

test('находка на уровне метода (глубокая вложенность) на блок-схеме → start', () => {
  const f = orders.diagnostics.findings.find((x) => x.rule === 'deep-nesting')!;
  const g = Object.values(orders.methods).find((m) => m.nodes.some((n) => n.id === `${f.nodeIds![0]}:start`))!;
  assert.equal(nodeForFinding(g, f)!.kind, 'start');
});

test('маркеры: каждая находка монолита попадает на какой-то блок обзора', () => {
  const map = findingsByNode(orders.overview, orders.diagnostics.findings);
  const placed = [...map.values()].reduce((a, l) => a + l.length, 0);
  assert.equal(placed, orders.diagnostics.findings.length);
  assert.ok([...map.keys()].every((id) => orders.overview.nodes.some((n) => n.id === id)));
});

test('строка → блок на настоящей схеме: строка условия в цикле — условие, закрывающая скобка метода — start', () => {
  const g = methodByName(orders, 'processOrders');
  const cond = g.nodes.find((n) => n.kind === 'condition' && n.parent && g.nodes.find((p) => p.id === n.parent)?.kind === 'loop')!;
  assert.equal(nodeAtLine(g, orders.file, cond.range!.startLine)?.id, cond.id);
  const start = g.nodes[0];
  assert.equal(nodeAtLine(g, orders.file, start.range!.endLine)?.id, start.id);
});

test('авто-исправления FixEngine: после каждого метод находится заново, изменённая строка — в его блок-схеме', async () => {
  const { default: fixes } = await import('../mocks/real/OrdersFixes.json', { with: { type: 'json' } });
  const { changedLineNumbers, findMethodAgain } = await import('./edit.ts');
  let applied = 0;
  for (const [id, rec] of Object.entries(fixes as Record<string, { rejected?: string; methodId?: string; diff?: string; after?: RealDemo }>)) {
    if (rec.rejected) {
      assert.match(rec.rejected, /вручную|небезопасно|меняется/, id);
      continue;
    }
    applied++;
    const after = rec.after!;
    assert.ok(rec.diff!.includes('\n+'), `${id}: в диффе есть добавленные строки`);
    assert.ok(after.diagnostics.findings.every((f) => f.id !== id), `${id}: исправленная находка ушла`);
    // метод, где была находка: id в исходном обзоре = id в обзоре после (правка ниже его начала) — проверяем через findMethodAgain
    const methodBefore = orders.overview.nodes.find((n) => n.label === after.overview.nodes.find((m) => m.id === rec.methodId)!.label)!;
    const again = findMethodAgain(orders.overview, methodBefore.id, after.overview);
    assert.equal(again, rec.methodId, id);
    // как в App: из изменённых строк берём первую, что попадает в блок-схему открытого метода
    const lines = changedLineNumbers(orders.source, after.source!);
    const hit = lines.map((l) => nodeAtLine(after.methods[rec.methodId!], orders.file, l)).find(Boolean);
    assert.ok(hit, `${id}: изменённые строки ${lines} → блок метода`);
  }
  assert.equal(applied, 4);
});
