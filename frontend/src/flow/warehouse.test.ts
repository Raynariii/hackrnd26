// ?demo=warehouse: записанная цепочка исправлений на corpus/06-demo-warehouse согласована и честна.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import data from '../mocks/real/Warehouse.json' with { type: 'json' };
import type { Diagnostics, Graph, RunResult, VerifyReport } from '../api/types';
import { nodeForFinding } from './sourceMap.ts';
import { speedup } from './verify.ts';

type State = { source: string; overview: Graph; methods: Record<string, Graph>; diagnostics: Diagnostics; runs: Record<string, RunResult>; verify: VerifyReport };
const wh = data as unknown as { file: string; states: State[]; transitions: Record<string, { to?: number; rule?: string; diff?: string; rejected?: string }>[] };
const O2 = ['collection-lookup-in-loop', 'string-concat-in-loop', 'linked-list-index-access'];

test('8 состояний, из любого порядка трёх исправлений — в одно финальное', () => {
  assert.equal(wh.states.length, 8);
  const reach = (i: number, seen = new Set<number>()): Set<number> => {
    seen.add(i);
    for (const t of Object.values(wh.transitions[i])) if (t.to !== undefined && !seen.has(t.to)) reach(t.to, seen);
    return seen;
  };
  assert.equal(reach(0).size, 8);
  const finals = wh.transitions.map((t, i) => (Object.keys(t).length === 0 ? i : -1)).filter((i) => i >= 0);
  assert.deepEqual(finals, [7]);
  for (const t of wh.transitions.flatMap((m) => Object.values(m))) {
    assert.ok(t.to! > 0 && t.to! < 8 && O2.includes(t.rule!) && t.diff!.includes('@@'));
  }
});

test('в финальном состоянии нет трёх O(n²), исправлено всё записанное', () => {
  const rules = (i: number) => wh.states[i].diagnostics.findings.map((f) => f.rule);
  for (const r of O2) assert.ok(rules(0).includes(r), r);
  for (const r of O2) assert.ok(!rules(7).includes(r), r);
});

test('каждое состояние: блок-схема на каждый метод, находки привязаны к узлам', () => {
  for (const s of wh.states) {
    const methods = s.overview.nodes.filter((n) => n.kind === 'method' || n.kind === 'constructor');
    assert.deepEqual(Object.keys(s.methods).sort(), methods.map((n) => n.id).sort());
    for (const f of s.diagnostics.findings) {
      const g = Object.values(s.methods).find((m) => nodeForFinding(m, f));
      assert.ok(g, `${f.rule} @${f.startLine}`);
    }
  }
});

test('поведение совпадает с v1 на входах корпуса, финал быстрее на большом n', () => {
  for (const s of wh.states) {
    assert.ok(s.verify.behaviorPreserved && s.verify.passed === s.verify.total && s.verify.total === 3);
    for (const c of s.verify.cases) assert.equal(c.actual!.stdout, c.expected!.stdout);
    assert.equal(Object.keys(s.runs).length, 3);
  }
  const sp = speedup(wh.states[7].verify.benchmark!)!;
  assert.equal(sp.n, 20000);
  assert.ok(sp.ratio > 3, String(sp.ratio));
});
