import type { Diagnostics, Graph, GraphNode } from '../api/types';

const POWER = ['O(1)', 'O(n)', 'O(n²)', 'O(n³)'];

/**
 * Оценка Big O выделения для демо без бэкенда: циклы-предки выделенного блока плюс самая глубокая вложенность
 * циклов внутри него. `<метод>:start` — весь метод. Настоящий POST /complexity считает точнее (MetricsCalculator).
 */
export function loopDepthComplexity(flow: Graph, nodeIds: string[]): string {
  const byId = new Map(flow.nodes.map((n) => [n.id, n]));
  const children = new Map<string, GraphNode[]>();
  for (const n of flow.nodes) if (n.parent) children.set(n.parent, [...(children.get(n.parent) ?? []), n]);

  const inner = (n: GraphNode): number =>
    (n.kind === 'loop' ? 1 : 0) + Math.max(0, ...(children.get(n.id) ?? []).map(inner));
  const outer = (n: GraphNode): number => {
    let depth = 0;
    for (let p = n.parent ? byId.get(n.parent) : undefined; p; p = p.parent ? byId.get(p.parent) : undefined) {
      if (p.kind === 'loop') depth++;
    }
    return depth;
  };

  const depth = Math.max(0, ...nodeIds.map((id) => {
    const node = byId.get(id);
    if (!node) return 0;
    if (node.kind === 'start') return Math.max(0, ...flow.nodes.filter((n) => !n.parent).map(inner));
    return outer(node) + inner(node);
  }));
  return POWER[depth] ?? `O(n^${depth})`;
}

/** Порядок классов сложности для выбора худшего: O(1) < O(log n) < O(n) < O(n log n) < O(n²) < … < O(2^n). */
export function complexityRank(c: string): number {
  const raw = c.replace(/\s+/g, '');
  if (/2\^n|n!/.test(raw)) return 100;
  // множитель log n — полшага; степень n читаем без него, иначе «log n» сам выглядит как n
  const s = raw.replace(/log\(?n\)?/g, '');
  const pow = s.match(/n(?:\^(\d+)|([²³]))/);
  const k = pow ? Number(pow[1] ?? (pow[2] === '²' ? 2 : 3)) : /n/.test(s) ? 1 : 0;
  return k + (raw.length !== s.length ? 0.5 : 0);
}

/**
 * Демо-ответ POST /complexity: худшее из оценки по циклам и того, что уже посчитал анализатор (записанная
 * диагностика): Big O метода из метрик, если выделен весь метод, и сложность находок внутри выделения.
 */
export function mockComplexity(flow: Graph, nodeIds: string[], diagnostics: Pick<Diagnostics, 'findings' | 'metrics'>): string {
  const candidates = [loopDepthComplexity(flow, nodeIds)];
  if (nodeIds.some((id) => id.endsWith(':start'))) {
    const metric = diagnostics.metrics.find((m) => m.methodId === flow.scope)?.complexity;
    if (metric) candidates.push(metric);
  }
  const inside = new Set<string>();
  const add = (n: GraphNode) => {
    inside.add(n.id);
    for (const c of (n.meta?.covers as string[] | undefined) ?? []) inside.add(c);
    for (const child of flow.nodes.filter((x) => x.parent === n.id)) add(child);
  };
  for (const n of flow.nodes) if (nodeIds.includes(n.id)) add(n);
  for (const f of diagnostics.findings) {
    if (f.complexity && f.nodeIds?.some((id) => inside.has(id))) candidates.push(f.complexity.current);
  }
  return candidates.reduce((a, b) => (complexityRank(b) > complexityRank(a) ? b : a));
}
