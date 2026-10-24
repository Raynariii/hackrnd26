import ELK from 'elkjs/lib/elk.bundled.js';
import { MarkerType, type Edge, type Node } from '@xyflow/react';
import type { Graph } from '../api/types';
import { buildFlowModel, collectBoxes, toElkGraph, type EdgeTone, type ElkNodeLike, type FlowItem } from '../flow/methodFlow';

const elk = new ELK();

/** Рёбра — графит; «Нет» — охрой ветвления, возврат к циклу — бирюзой, исключение — красным (как в BlockNode). */
const EDGE_COLORS: Record<EdgeTone, string> = {
  yes: '#465163',
  no: '#8a5300',
  back: '#0b6b6b',
  exception: '#b42318',
  call: '#7d8796',
  contains: '#c5ccd6',
  default: '#465163',
};

/**
 * Раскладка графа через elkjs. Для блок-схемы метода узлы с детьми (parent) становятся рамками-группами:
 * тело цикла, try/catch, switch. Обратные рёбра (возврат к циклу) в раскладке не участвуют и рисуются сбоку.
 */
export async function layoutGraph(graph: Graph): Promise<{ nodes: Node[]; edges: Edge[] }> {
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
    const color = EDGE_COLORS[fe.tone];
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
      style: {
        stroke: color,
        strokeWidth: 1.25,
        ...(fe.back || fe.tone === 'call' ? { strokeDasharray: '6 4' } : {}),
        ...(floating ? { opacity: 0.45 } : {}),
      },
      labelStyle: { fill: color, fontWeight: 500, fontSize: 11 },
      labelBgStyle: { fill: '#ffffff' },
      labelBgPadding: [4, 2] as [number, number],
      markerEnd: fe.tone === 'contains' ? undefined : { type: MarkerType.ArrowClosed, color, width: 14, height: 14 },
      // Небольшой отступ поворота: при коротком зазоре стандартные 20px закручивают стрелку в петлю.
      pathOptions: { offset: 10, borderRadius: 6 },
      zIndex: 1,
    };
  });

  return { nodes, edges };
}
