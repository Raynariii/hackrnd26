import { test } from 'node:test';
import assert from 'node:assert/strict';
import data from './real/OrdersMonolith.json' with { type: 'json' };
import type { Graph } from '../api/types';
import { loopDepthComplexity } from './complexity.ts';

const methods = (data as unknown as { methods: Record<string, Graph> }).methods;
const dup = methods.ga133; // duplicateCheck: два вложенных for по orders

test('весь метод (start) — по самой глубокой вложенности циклов', () => {
  assert.equal(loopDepthComplexity(dup, ['ga133:start']), 'O(n²)');
  assert.equal(loopDepthComplexity(methods.ga204, ['ga204:start']), 'O(n)');
  assert.equal(loopDepthComplexity(methods.ga361, ['ga361:start']), 'O(1)');
});

test('блок вне циклов — O(1), внешний цикл — вместе с внутренним, тело внутреннего — оба предка', () => {
  assert.equal(loopDepthComplexity(dup, ['ga146']), 'O(1)');
  assert.equal(loopDepthComplexity(dup, ['ga157']), 'O(n²)');
  assert.equal(loopDepthComplexity(dup, ['ga194']), 'O(n²)');
});

test('несколько блоков — максимум; неизвестный id не ломает оценку', () => {
  assert.equal(loopDepthComplexity(dup, ['ga146', 'ga201']), 'O(1)');
  assert.equal(loopDepthComplexity(dup, ['ga146', 'ga166']), 'O(n²)');
  assert.equal(loopDepthComplexity(dup, ['nope']), 'O(1)');
});

import { complexityRank, mockComplexity } from './complexity.ts';
import type { Diagnostics } from '../api/types';
const diag = (data as unknown as { diagnostics: Diagnostics }).diagnostics;

test('complexityRank упорядочивает классы', () => {
  const order = ['O(1)', 'O(log n)', 'O(n)', 'O(n log n)', 'O(n²)', 'O(n^2 log n)', 'O(n³)', 'O(n^4)', 'O(2^n)'];
  for (let i = 1; i < order.length; i++) assert.ok(complexityRank(order[i]) > complexityRank(order[i - 1]), order[i]);
});

test('демо /complexity учитывает оценки анализатора: метрику метода и находки внутри выделения', () => {
  const orders = methods.ga204; // processOrders: один for, внутри contains по списку
  assert.equal(mockComplexity(orders, ['ga204:start'], diag), 'O(n²)'); // метрика метода
  assert.equal(mockComplexity(orders, ['ga228'], diag), 'O(n²)'); // цикл с contains внутри
  assert.equal(mockComplexity(orders, ['ga237'], diag), 'O(n²)'); // условие с contains (ga238 в covers)
  assert.equal(mockComplexity(orders, ['ga215'], diag), 'O(1)');
  assert.equal(mockComplexity(dup, ['ga133:start'], diag), 'O(n³)');
});
