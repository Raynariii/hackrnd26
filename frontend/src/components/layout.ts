import ELK from 'elkjs/lib/elk.bundled.js';
import { MarkerType, type Edge, type Node } from '@xyflow/react';
import type { Graph } from '../api/types';
import { buildFlowModel, collectBoxes, toElkGraph, type EdgeTone, type ElkNodeLike, type FlowItem } from '../flow/methodFlow';

const elk = new ELK();

/**
 * Цвета рёбер для светлой и тёмной темы (маркеры стрелок React Flow берут цвет строкой в id — CSS-переменная туда
 * не годится, поэтому hex; при смене темы GraphCanvas перекрашивает рёбра без новой раскладки).
 */
export type Theme = 'light' | 'dark';
export const EDGE_COLORS: Record<Theme, Record<EdgeTone, string>> = {
  light: { yes: '#7d838e', no: '#9e5c00', back: '#00627a', exception: '#c7222d', call: '#8b919c', contains: '#c9ced6', default: '#7d838e' },
  dark: { yes: '#6b7078', no: '#d39a52', back: '#4fa9bb', exception: '#e0717a', call: '#6b7078', contains: '#3a3d42', default: '#6b7078' },
};

/** Стиль ребра по тону и теме: общий для раскладки и для перекраски при смене темы. */
export function edgePaint(tone: EdgeTone, theme: Theme, base: Partial<Edge> = {}): Partial<Edge> {
  const color = EDGE_COLORS[theme][tone];
  return {
    style: { ...(base.style ?? {}), stroke: color },
    labelStyle: { fill: color, fontWeight: 500, fontSize: 11 },
    markerEnd: tone === 'contains' ? undefined : { type: MarkerType.ArrowClosed, color, width: 14, height: 14 },
  };
}

/**
 * Раскладка графа через elkjs. Для блок-схемы метода узлы с детьми (parent) становятся рамками-группами:
 * тело цикла, try/catch, switch. Обратные рёбра (возврат к циклу) в раскладке не участвуют и рисуются сбоку.
 */
export async function layoutGraph(graph: Graph, theme: Theme = 'light'): Promise<{ nodes: Node[]; edges: Edge[] }> {
  const overview = graph.level === 'overview';
  // Обзор: типы — рамки с членами, упакованные сеткой; вызовы — поверх раскладки (иначе плоская лента).
  const model = buildFlowModel(graph, { grouping: true, packed: overview });
  const layout = (await elk.layout(toElkGraph(model) as unknown as Parameters<typeof elk.layout>[0])) as unknown as ElkNodeLike;
  const boxes = collectBoxes(layout);

  const nodes: Node[] = model.items.map((item: FlowItem) => {
    const box = boxes.get(item.id) ?? { x: 0, y: 0, width: 0, height: 0 };
    const base = {
      id: item.id,
      position: { x: box.x, y: box.y },
      ...(item.parent ? { parentId: item.parent } : {}),
    };
    if (item.type === 'frame') {
      return {
        ...base,
        type: 'frame',
        data: { node: item.node, frame: true },
        style: { width: box.width, height: box.height },
        width: box.width,
        height: box.height,
        selectable: false,
        draggable: false,
      };
    }
    return { ...base, type: 'block', data: { node: item.node, size: item.size } };
  });

  const edges: Edge[] = model.edges.map((fe) => {
    // Вызовы на обзоре: кривые поверх сетки, приглушённые; у выделенного метода подсвечиваются (GraphCanvas).
    const floating = !fe.inLayout && !fe.back;
    return {
      id: fe.edge.id,
      source: fe.edge.from,
      target: fe.edge.to,
      sourceHandle: fe.sourceHandle,
      targetHandle: fe.targetHandle,
      type: floating ? 'default' : 'smoothstep',
      data: { tone: fe.tone, floating },
      label: fe.label ?? undefined,
      className: `edge-${fe.tone}`,
      labelBgPadding: [4, 2] as [number, number],
      ...edgePaint(fe.tone, theme, {
        style: {
          strokeWidth: 1.25,
          ...(fe.back || fe.tone === 'call' ? { strokeDasharray: '6 4' } : {}),
          ...(floating ? { opacity: 0.45 } : {}),
        },
      }),
      // Небольшой отступ поворота: при коротком зазоре стандартные 20px закручивают стрелку в петлю.
      pathOptions: { offset: 10, borderRadius: 6 },
      zIndex: 1,
    };
  });

  return { nodes, edges };
}
