import { useEffect, useState } from 'react';
import { Background, Controls, MiniMap, ReactFlow, type Edge, type Node } from '@xyflow/react';
import type { Graph, GraphNode } from '../api/types';
import { BlockNode, FrameNode, kindColor } from './BlockNode';
import { layoutGraph } from './layout';

const nodeTypes = { block: BlockNode, frame: FrameNode };

interface Props {
  graph: Graph | null;
  selectedId: string | null;
  onSelect: (node: GraphNode) => void;
  onOpenMethod: (node: GraphNode) => void;
}

interface Laid {
  nodes: Node[];
  edges: Edge[];
  /** Счётчик раскладок: новый граф → новый key → ReactFlow заново делает fitView. */
  rev: number;
}

export function GraphCanvas({ graph, selectedId, onSelect, onOpenMethod }: Props) {
  const [laid, setLaid] = useState<Laid | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!graph) return;
    let cancelled = false;
    layoutGraph(graph)
      .then((result) => {
        if (cancelled) return;
        setError(null);
        setLaid((prev) => ({ ...result, rev: (prev?.rev ?? 0) + 1 }));
      })
      .catch((e: unknown) => !cancelled && setError(`Не удалось разложить граф: ${String(e)}`));
    return () => {
      cancelled = true;
    };
  }, [graph]);

  if (error) return <div className="empty error">{error}</div>;
  if (!laid) return <div className="empty">Раскладываем граф…</div>;

  const nodes = laid.nodes.map((n) => (n.type === 'block' ? { ...n, selected: n.id === selectedId } : n));
  const nodeOf = (n: Node) => (n.data as { node: GraphNode }).node;

  return (
    <ReactFlow
      key={laid.rev}
      nodes={nodes}
      edges={laid.edges}
      nodeTypes={nodeTypes}
      onNodeClick={(_, n) => onSelect(nodeOf(n))}
      onNodeDoubleClick={(_, n) => {
        const node = nodeOf(n);
        if (node.kind === 'method' || node.kind === 'constructor') onOpenMethod(node);
      }}
      fitView
      minZoom={0.1}
      colorMode="dark"
    >
      <Background />
      <Controls />
      <MiniMap pannable zoomable nodeColor={(n) => kindColor(nodeOf(n).kind)} />
    </ReactFlow>
  );
}
