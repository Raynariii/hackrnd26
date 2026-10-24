// Связь «строка кода ↔ узел графа» по полю range (contracts/graph.schema.json). Без React — тестируется в node.
import type { Finding, Graph, GraphNode } from '../api/types';

const span = (n: GraphNode) => n.range!.endLine - n.range!.startLine;

/** Глубина по полю parent (с защитой от циклов): при равном охвате строк выигрывает более вложенный узел. */
function depth(n: GraphNode, byId: Map<string, GraphNode>): number {
  let d = 0;
  const seen = new Set<string>([n.id]);
  let p = n.parent ? byId.get(n.parent) : undefined;
  while (p && !seen.has(p.id)) {
    d++;
    seen.add(p.id);
    p = p.parent ? byId.get(p.parent) : undefined;
  }
  return d;
}

/** Синтетические узлы блок-схемы: в коде им соответствует только заголовок/граница метода. */
const SYNTHETIC = new Set<GraphNode['kind']>(['start', 'end']);

/**
 * Самый точный узел для строки: из узлов, чей range содержит строку, — с самым коротким охватом,
 * затем самый вложенный, затем не start/end. Например, строка условия внутри цикла → условие, а не цикл;
 * строка в теле метода на обзорном графе → метод, а не класс.
 */
export function nodeAtLine(graph: Graph, file: string, line: number): GraphNode | null {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  let best: GraphNode | null = null;
  for (const n of graph.nodes) {
    const r = n.range;
    if (!r || r.file !== file || line < r.startLine || line > r.endLine) continue;
    if (!best || better(n, best, byId)) best = n;
  }
  return best;
}

function better(a: GraphNode, b: GraphNode, byId: Map<string, GraphNode>): boolean {
  if (span(a) !== span(b)) return span(a) < span(b);
  const da = depth(a, byId);
  const db = depth(b, byId);
  if (da !== db) return da > db;
  return SYNTHETIC.has(b.kind) && !SYNTHETIC.has(a.kind);
}

/** id выражений внутри блока (meta.covers из graph.schema.json): анализатор ссылается на них, а не на операторы. */
function covers(n: GraphNode): string[] {
  const c = n.meta?.covers;
  return Array.isArray(c) ? c.filter((x): x is string => typeof x === 'string') : [];
}

/**
 * Блок текущего графа для находки. Finding.nodeIds указывают на AST-узлы (часто выражения, например
 * `ys.contains(x)`), поэтому подходит блок, у которого id или meta.covers содержит один из них;
 * у `:start` в covers — id метода. Из подходящих — самый точный (как в nodeAtLine).
 * Если ни один блок не подошёл (старый граф без covers) — по строке начала находки.
 */
export function nodeForFinding(graph: Graph, finding: Finding): GraphNode | null {
  const ids = new Set(finding.nodeIds ?? []);
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  let best: GraphNode | null = null;
  for (const n of graph.nodes) {
    if (!ids.has(n.id) && !covers(n).some((c) => ids.has(c))) continue;
    if (!best || (n.range && (!best.range || better(n, best, byId)))) best = n;
  }
  return best ?? nodeAtLine(graph, finding.file, finding.startLine);
}

/** Находки, разложенные по блокам текущего графа — для маркеров на узлах. */
export function findingsByNode(graph: Graph, findings: Finding[]): Map<string, Finding[]> {
  const result = new Map<string, Finding[]>();
  for (const f of findings) {
    const node = nodeForFinding(graph, f);
    if (!node) continue;
    const list = result.get(node.id);
    if (list) list.push(f);
    else result.set(node.id, [f]);
  }
  return result;
}
