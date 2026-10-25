// Два вида обзора монолита из одного overview-графа ядра (без React и elkjs — тестируется в node).
//  • «Сценарий» — что программа делает: от main по вызовам, в порядке вызова, каждый метод один раз.
//  • «Разделы» — как код устроен: члены класса в рамках разделов из баннерных комментариев (meta.section).
import type { Graph, GraphEdge, GraphNode } from '../api/types';

export type OverviewMode = 'scenario' | 'sections';

const isCallable = (n: GraphNode) => n.kind === 'method' || n.kind === 'constructor';
const metaText = (n: GraphNode, key: string): string | null => {
  const v = n.meta?.[key];
  return typeof v === 'string' && v.trim() ? v : null;
};

/**
 * Сценарий: корни — точка входа (meta.entry), затем методы, которые никто не вызывает (в порядке кода).
 * Обход в ширину по рёбрам call в порядке ядра (порядок вызовов в коде), номер на ребре — шаг внутри метода.
 * Поля и типы не показываются; в блоке вместо сигнатуры — описание из javadoc или раздел.
 */
export interface Scenario {
  graph: Graph;
  /** Повторные вызовы (метод уже стоит в сценарии): не в раскладке, видны у выделенного метода. */
  cross: Set<string>;
}

export function scenarioGraph(overview: Graph): Scenario {
  const callables = overview.nodes.filter(isCallable);
  const byId = new Map(callables.map((n) => [n.id, n]));
  const calls = overview.edges.filter((e) => e.kind === 'call' && byId.has(e.from) && byId.has(e.to) && e.from !== e.to);
  const out = new Map<string, GraphEdge[]>();
  const incoming = new Set<string>();
  for (const e of calls) {
    out.set(e.from, [...(out.get(e.from) ?? []), e]);
    incoming.add(e.to);
  }

  const line = (n: GraphNode) => n.range?.startLine ?? Number.MAX_SAFE_INTEGER;
  const inCodeOrder = [...callables].sort((a, b) => line(a) - line(b));
  const entries = inCodeOrder.filter((n) => n.meta?.entry === true);
  const roots = [...entries, ...inCodeOrder.filter((n) => !incoming.has(n.id) && !entries.includes(n))];

  const seen = new Set<string>();
  const order: string[] = [];
  const edges: GraphEdge[] = [];
  const cross = new Set<string>();
  const unreachedRoots = new Set<string>();
  const visit = (root: GraphNode, fromEntry: boolean) => {
    if (seen.has(root.id)) return;
    if (!fromEntry) unreachedRoots.add(root.id);
    seen.add(root.id);
    const queue = [root.id];
    while (queue.length) {
      const id = queue.shift()!;
      order.push(id);
      (out.get(id) ?? []).forEach((e, i) => {
        const step = String(i + 1);
        const label = e.label ? `${step} · ${e.label}` : step;
        const tree = !seen.has(e.to);
        edges.push({ ...e, label });
        if (!tree) cross.add(e.id);
        if (tree) {
          seen.add(e.to);
          queue.push(e.to);
        }
      });
    }
  };
  entries.forEach((n) => visit(n, true));
  roots.forEach((n) => visit(n, false));
  // Методы только в циклах вызовов (a ↔ b) без внешнего входа — тоже показываем.
  inCodeOrder.forEach((n) => visit(n, false));

  const hasEntry = entries.length > 0;
  const nodes = order.map((id) => {
    const n = byId.get(id)!;
    // Точка входа — с сигнатурой (раздел над main обычно чужой: main стоит в конце файла без своего баннера).
    const note = n.meta?.entry === true ? n.code ?? null : metaText(n, 'doc') ?? metaText(n, 'section');
    const unreached = hasEntry && unreachedRoots.has(id) ? 'Не вызывается из main' : null;
    return {
      ...n,
      parent: null,
      code: [unreached, note].filter(Boolean).join(' · ') || n.code,
      meta: { ...n.meta, signature: n.code ?? null },
    } as GraphNode;
  });
  return { graph: { ...overview, nodes, edges }, cross };
}

export const SECTION_PREFIX = 'section:';

/**
 * Разделы: у членов с meta.section родитель — синтетическая рамка раздела внутри их типа (meta.sectionFrame).
 * Члены без раздела остаются в типе как были.
 */
export function sectionsGraph(overview: Graph): Graph {
  const frames = new Map<string, GraphNode>();
  const nodes = overview.nodes.map((n) => {
    const section = metaText(n, 'section');
    if (!section || !n.parent) return n;
    const id = `${SECTION_PREFIX}${n.parent}:${section}`;
    if (!frames.has(id)) {
      frames.set(id, {
        id,
        kind: 'class',
        label: section,
        parent: n.parent,
        range: n.range,
        meta: { sectionFrame: true },
      } as GraphNode);
    }
    return { ...n, parent: id };
  });
  return { ...overview, nodes: [...frames.values(), ...nodes] };
}

export const isSectionFrame = (n: GraphNode) => n.meta?.sectionFrame === true;
