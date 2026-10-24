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

/**
 * Узел текущего графа для находки: сперва по Finding.nodeIds (соглашение id = 'g' + astId),
 * иначе — по строке начала находки.
 */
export function nodeForFinding(graph: Graph, finding: Finding): GraphNode | null {
  const ids = new Set(finding.nodeIds ?? []);
  const linked = graph.nodes.filter((n) => ids.has(n.id));
  if (linked.length) {
    // Если на этом уровне графа привязано несколько узлов — берём самый точный по строкам.
    return linked.reduce((best, n) => (!n.range ? best : !best.range || span(n) < span(best) ? n : best));
  }
  return nodeAtLine(graph, finding.file, finding.startLine);
}
