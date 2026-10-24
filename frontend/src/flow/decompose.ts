// Предложение декомпозиции (POST /decompose, DecomposeResult) ↔ узлы обзорного графа. Без React — тесты в node.
import type { DecomposeResult, Graph, GraphNode } from '../api/types';

export type ProposedClass = DecomposeResult['proposal'][number];

/** Число параметров в сигнатуре «name(A, B<C, D>)» — запятые внутри <> не считаются. */
function arity(params: string): number {
  if (!params.trim()) return 0;
  let depth = 0;
  let count = 1;
  for (const ch of params) {
    if (ch === '<') depth++;
    else if (ch === '>') depth--;
    else if (ch === ',' && depth === 0) count++;
  }
  return count;
}

const paramsOf = (code: string | null | undefined) => {
  const m = /\(([^]*)\)/.exec(code ?? '');
  return m ? m[1] : '';
};

/**
 * Узел обзора для члена из предложения. Decomposer пишет методы как «processOrders(List)», поля — «field counter».
 * Метод сопоставляем по имени и числу параметров (перегрузки), поле — по имени.
 */
export function memberNode(overview: Graph, member: string): GraphNode | null {
  const field = /^field\s+(.+)$/.exec(member.trim());
  if (field) return overview.nodes.find((n) => n.kind === 'field' && n.label === field[1]) ?? null;
  const call = /^([\w$]+)\s*\(([^]*)\)$/.exec(member.trim());
  if (!call) return null;
  const [, name, params] = call;
  const candidates = overview.nodes.filter((n) => (n.kind === 'method' || n.kind === 'constructor') && n.label === name);
  return candidates.find((n) => arity(paramsOf(n.code)) === arity(params)) ?? candidates[0] ?? null;
}

/** Члены, которые Decomposer положил в несколько классов (обычно общее поле) — их решают вручную. */
export function sharedMembers(proposal: ProposedClass[]): Set<string> {
  const seen = new Map<string, number>();
  for (const c of proposal) for (const m of new Set(c.members)) seen.set(m, (seen.get(m) ?? 0) + 1);
  return new Set([...seen].filter(([, n]) => n > 1).map(([m]) => m));
}

/** Узлы обзора для класса предложения (неопознанные члены пропускаются). */
export function classNodes(overview: Graph, cls: ProposedClass): GraphNode[] {
  const out: GraphNode[] = [];
  for (const m of cls.members) {
    const n = memberNode(overview, m);
    if (n && !out.includes(n)) out.push(n);
  }
  return out;
}
