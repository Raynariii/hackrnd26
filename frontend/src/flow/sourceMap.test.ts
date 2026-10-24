import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Finding, Graph } from '../api/types';
import { mockDiagnostics, mockMethodFlow, mockOverview } from '../mocks/fixtures.ts';
import { nodeAtLine, nodeForFinding } from './sourceMap.ts';

const id = (g: Graph, line: number, file = 'Main.java') => nodeAtLine(g, file, line)?.id ?? null;

test('блок-схема: строка → самый вложенный узел', () => {
  assert.equal(id(mockMethodFlow, 5), 'n1'); // переменная
  assert.equal(id(mockMethodFlow, 6), 'n2'); // условие
  assert.equal(id(mockMethodFlow, 9), 'n4'); // заголовок цикла (9–13)
  assert.equal(id(mockMethodFlow, 10), 'n5'); // условие внутри цикла, а не цикл
  assert.equal(id(mockMethodFlow, 12), 'n4'); // закрывающая скобка if внутри цикла → цикл
});

test('строка вне метода или в другом файле — узла нет', () => {
  assert.equal(id(mockMethodFlow, 18), null);
  assert.equal(id(mockMethodFlow, 10, 'Other.java'), null);
});

test('обзор: строка в теле метода → метод, а не класс', () => {
  assert.equal(id(mockOverview, 10), 'gm1');
  assert.equal(id(mockOverview, 18), 'gm2');
  assert.equal(id(mockOverview, 3), 'gc');
});

test('при равном охвате: вложенный узел, затем не start/end', () => {
  const r = { file: 'A.java', startLine: 4, endLine: 4 };
  const g: Graph = {
    level: 'method',
    nodes: [
      { id: 'm:start', kind: 'start', label: 'Метод', range: r },
      { id: 'v', kind: 'variable', label: 'Переменная', range: r },
      { id: 'loop', kind: 'loop', label: 'Цикл', range: r },
      { id: 'inner', kind: 'operation', label: 'Операция', range: r, parent: 'loop' },
    ],
    edges: [],
  };
  assert.equal(nodeAtLine(g, 'A.java', 4)?.id, 'inner');
  g.nodes.pop();
  g.nodes.pop();
  assert.equal(nodeAtLine(g, 'A.java', 4)?.id, 'v');
});

test('находка → узел по nodeIds на текущем уровне графа, иначе по строке', () => {
  const f = mockDiagnostics.findings[0]; // nodeIds: n5 (блок-схема) и gm1 (обзор)
  assert.equal(nodeForFinding(mockMethodFlow, f)?.id, 'n5');
  assert.equal(nodeForFinding(mockOverview, f)?.id, 'gm1');
  const unlinked: Finding = { ...f, nodeIds: [], startLine: 6, endLine: 6 };
  assert.equal(nodeForFinding(mockMethodFlow, unlinked)?.id, 'n2');
});
