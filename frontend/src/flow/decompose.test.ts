// Предложения настоящего Decomposer (main) на OrdersMonolith и ScopeSample — mocks/real/Decompose.json.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import decomp from '../mocks/real/Decompose.json' with { type: 'json' };
import orders from '../mocks/real/OrdersMonolith.json' with { type: 'json' };
import scope from '../mocks/real/ScopeSample.json' with { type: 'json' };
import type { DecomposeResult, Graph } from '../api/types';
import { classNodes, memberNode, sharedMembers } from './decompose.ts';

const d = decomp as unknown as Record<string, DecomposeResult>;
const ordersOv = (orders as unknown as { overview: Graph }).overview;
const scopeOv = (scope as unknown as { overview: Graph }).overview;

test('каждый член предложения находит свой узел на обзоре', () => {
  for (const [file, ov] of [['OrdersMonolith.java', ordersOv], ['ScopeSample.java', scopeOv]] as const) {
    for (const c of d[file].proposal) {
      for (const m of c.members) assert.ok(memberNode(ov, m), `${file}: ${c.className} / ${m}`);
    }
  }
});

test('метод — по имени и числу параметров, поле — по имени', () => {
  assert.equal(memberNode(ordersOv, 'classify(boolean, boolean, boolean, boolean, boolean)')?.label, 'classify');
  assert.equal(memberNode(ordersOv, 'field processed')?.kind, 'field');
  assert.equal(memberNode(ordersOv, 'nope(int)'), null);
  assert.equal(memberNode(ordersOv, 'field nope'), null);
});

test('общий член нескольких классов: counter в ScopeSample', () => {
  assert.deepEqual([...sharedMembers(d['ScopeSample.java'].proposal)], ['field counter']);
  assert.equal(sharedMembers(d['OrdersMonolith.java'].proposal).size, 0);
});

test('classNodes: узлы класса без повторов', () => {
  const utils = d['OrdersMonolith.java'].proposal.find((c) => c.className === 'Utils')!;
  assert.deepEqual(classNodes(ordersOv, utils).map((n) => n.label), ['processOrders', 'classify', 'sumLengths', 'sumLengthsAgain']);
});
