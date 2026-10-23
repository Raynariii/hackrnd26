import { useEffect, useState } from 'react';
import { Background, Controls, MiniMap, ReactFlow, type Edge, type Node } from '@xyflow/react';
import type { Graph, GraphNode } from '../api/types';
import { BlockNode } from './BlockNode';
import { layoutGraph } from './layout';

const nodeTypes = { block: BlockNode };

interface Props {
  graph: Graph | null;
  selectedId: string | null;
  onSelect: (node: GraphNode) => void;
  onOpenMethod: (node: GraphNode) => void;
}

export function GraphCanvas({ graph, selectedId, onSelect, onOpenMethod }: Props) {
  const [nodes, setNodes] = useState<Node[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);

  useEffect(() => {
    if (!graph) return;
    let cancelled = false;
    layoutGraph(graph).then((result) => {
      if (!cancelled) {
        setNodes(result.nodes);
        setEdges(result.edges);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [graph]);

  const shown = nodes.map((n) => ({ ...n, selected: n.id === selectedId }));

  return (
    <ReactFlow
      nodes={shown}
      edges={edges}
      nodeTypes={nodeTypes}
      onNodeClick={(_, n) => onSelect((n.data as { node: GraphNode }).node)}
      onNodeDoubleClick={(_, n) => {
        const node = (n.data as { node: GraphNode }).node;
        if (node.kind === 'method' || node.kind === 'constructor') onOpenMethod(node);
      }}
      fitView
      colorMode="dark"
    >
      <Background />
      <Controls />
      <MiniMap pannable zoomable />
    </ReactFlow>
  );
}
