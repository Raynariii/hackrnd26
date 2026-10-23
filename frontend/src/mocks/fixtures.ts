import type { Diagnostics, Graph, ProjectState, RunResult, VerifyReport } from '../api/types';

const MAIN = `import java.util.*;

public class Main {
    static List<Integer> processOrders(List<Integer> orders) {
        List<Integer> result = new ArrayList<>();
        if (orders == null || orders.isEmpty()) {
            return result;
        }
        for (int order : orders) {
            if (!result.contains(order)) {
                result.add(order * 2);
            }
        }
        return result;
    }

    public static void main(String[] args) {
        System.out.println(processOrders(List.of(1, 2, 2, 3)));
    }
}
`;

export const mockProject: ProjectState = {
  id: 'mock',
  name: 'demo',
  version: 1,
  files: [{ path: 'Main.java', content: MAIN }],
  parseProblems: [],
};

const r = (startLine: number, endLine = startLine) => ({ file: 'Main.java', startLine, endLine });

export const mockOverview: Graph = {
  level: 'overview',
  scope: null,
  nodes: [
    { id: 'gc', kind: 'class', label: 'Main', range: r(3, 20) },
    { id: 'gm1', kind: 'method', label: 'processOrders', code: 'static List<Integer> processOrders(List<Integer> orders)', range: r(4, 15), parent: 'gc', findings: ['f1'] },
    { id: 'gm2', kind: 'method', label: 'main', code: 'public static void main(String[] args)', range: r(17, 19), parent: 'gc' },
  ],
  edges: [
    { id: 'e1', from: 'gc', to: 'gm1', kind: 'contains' },
    { id: 'e2', from: 'gc', to: 'gm2', kind: 'contains' },
    { id: 'e3', from: 'gm2', to: 'gm1', kind: 'call' },
  ],
};

export const mockMethodFlow: Graph = {
  level: 'method',
  scope: 'gm1',
  nodes: [
    { id: 'n0', kind: 'start', label: 'Метод', code: 'List<Integer> processOrders(List<Integer> orders)', range: r(4) },
    { id: 'n1', kind: 'variable', label: 'Переменная', code: 'List<Integer> result = new ArrayList<>()', range: r(5) },
    { id: 'n2', kind: 'condition', label: 'Условие', code: 'orders == null || orders.isEmpty()', range: r(6) },
    { id: 'n3', kind: 'return', label: 'Возврат', code: 'return result', range: r(7) },
    { id: 'n4', kind: 'loop', label: 'Цикл (for each)', code: 'for (int order : orders)', range: r(9, 13) },
    { id: 'n5', kind: 'condition', label: 'Условие', code: '!result.contains(order)', range: r(10), parent: 'n4', findings: ['f1'] },
    { id: 'n6', kind: 'call', label: 'Операция', code: 'result.add(order * 2)', range: r(11), parent: 'n4' },
    { id: 'n7', kind: 'return', label: 'Возврат', code: 'return result', range: r(14) },
  ],
  edges: [
    { id: 'e0', from: 'n0', to: 'n1', kind: 'next' },
    { id: 'e1', from: 'n1', to: 'n2', kind: 'next' },
    { id: 'e2', from: 'n2', to: 'n3', kind: 'true', label: 'Да' },
    { id: 'e3', from: 'n2', to: 'n4', kind: 'false', label: 'Нет' },
    { id: 'e4', from: 'n4', to: 'n5', kind: 'loopBody' },
    { id: 'e5', from: 'n5', to: 'n6', kind: 'true', label: 'Да' },
    { id: 'e6', from: 'n6', to: 'n4', kind: 'loopBack' },
    { id: 'e7', from: 'n5', to: 'n4', kind: 'false', label: 'Нет' },
    { id: 'e8', from: 'n4', to: 'n7', kind: 'loopExit' },
  ],
};

export const mockDiagnostics: Diagnostics = {
  version: 1,
  findings: [
    {
      id: 'f1',
      rule: 'list-contains-in-loop',
      severity: 'high',
      title: 'Вложенность O(n²)',
      explanation: 'result.contains() внутри цикла по orders — линейный поиск на каждой итерации.',
      file: 'Main.java',
      startLine: 10,
      endLine: 10,
      nodeIds: ['n5', 'gm1'],
      complexity: { current: 'O(n²)', after: 'O(n)' },
      fix: { kind: 'use-hash-set', description: 'Завести HashSet для проверки вхождения', autoApplicable: true },
      expectedEffect: 'до 5x быстрее при n=10⁴',
    },
  ],
  metrics: [
    { methodId: 'gm1', name: 'processOrders', file: 'Main.java', lines: 12, cyclomatic: 4, maxNesting: 2, complexity: 'O(n²)' },
    { methodId: 'gm2', name: 'main', file: 'Main.java', lines: 3, cyclomatic: 1, maxNesting: 0, complexity: 'O(1)' },
  ],
};

export const mockRun: RunResult = {
  compile: { ok: true, problems: [] },
  stdout: '[2, 6]\n',
  stderr: '',
  exitCode: 0,
  timeMs: 142,
  timedOut: false,
};

export const mockVerify: VerifyReport = {
  baselineVersion: 1,
  currentVersion: 1,
  compiled: true,
  behaviorPreserved: true,
  passed: 3,
  total: 3,
  cases: [
    { name: 'stdin: пусто', ok: true },
    { name: 'fuzz #1', ok: true },
    { name: 'fuzz #2', ok: true },
  ],
};
