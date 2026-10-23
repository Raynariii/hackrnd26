import ELK from 'elkjs/lib/elk.bundled.js';
import type { Edge, Node } from '@xyflow/react';
import type { Graph } from '../api/types';

const elk = new ELK();
const NODE_WIDTH = 260;
const NODE_HEIGHT = 64;

/** Раскладка графа сверху вниз через elkjs. Вложенность (parent) пока только визуальная — TODO(роль 3). */
export async function layoutGraph(graph: Graph): Promise<{ nodes: Node[]; edges: Edge[] }> {
  const layout = await elk.layout({
    id: 'root',
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'DOWN',
      'elk.spacing.nodeNode': '40',
      'elk.layered.spacing.nodeNodeBetweenLayers': '50',
    },
    children: graph.nodes.map((n) => ({ id: n.id, width: NODE_WIDTH, height: NODE_HEIGHT })),
    edges: graph.edges
      .filter((e) => e.kind !== 'loopBack')
      .map((e) => ({ id: e.id, sources: [e.from], targets: [e.to] })),
  });
  const positions = new Map(layout.children?.map((c) => [c.id, { x: c.x ?? 0, y: c.y ?? 0 }]));

  return {
    nodes: graph.nodes.map((n) => ({
      id: n.id,
      type: 'block',
      position: positions.get(n.id) ?? { x: 0, y: 0 },
      data: { node: n },
    })),
    edges: graph.edges.map((e) => ({
      id: e.id,
      source: e.from,
      target: e.to,
      label: e.label ?? undefined,
      animated: e.kind === 'loopBack',
      style: e.kind === 'call' ? { strokeDasharray: '6 4' } : undefined,
    })),
  };
}
