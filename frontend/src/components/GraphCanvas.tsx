import { useEffect, useState } from 'react';
import { Background, Controls, MiniMap, ReactFlow, useEdgesState, useNodesState, type Edge, type Node } from '@xyflow/react';
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

const nodeOf = (n: Node) => (n.data as { node: GraphNode }).node;

export function GraphCanvas({ graph, selectedId, onSelect, onOpenMethod }: Props) {
  // Узлы живут в состоянии и принимают изменения от React Flow (размеры, выделение). Если отдавать новые
  // объекты на каждый рендер без onNodesChange, React Flow теряет замеры и на кадр прячет узлы —
  // второй клик двойного клика уходит в пустой холст, и метод не открывается.
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  /** Счётчик раскладок: новый граф → новый key → ReactFlow заново делает fitView. */
  const [rev, setRev] = useState(0);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!graph) return;
    let cancelled = false;
    layoutGraph(graph)
      .then((result) => {
        if (cancelled) return;
        setError(null);
        setNodes(result.nodes);
        setEdges(result.edges);
        setRev((r) => r + 1);
      })
      .catch((e: unknown) => !cancelled && setError(`Не удалось разложить граф: ${String(e)}`));
    return () => {
      cancelled = true;
    };
  }, [graph, setNodes, setEdges]);

  // Выделение задаёт App (клик по узлу, находке, строке кода): меняем только узлы, у которых оно поменялось.
  useEffect(() => {
    setNodes((nds) =>
      nds.map((n) => {
        const want = n.type === 'block' && n.id === selectedId;
        return Boolean(n.selected) === want ? n : { ...n, selected: want };
      }),
    );
  }, [selectedId, rev, setNodes]);

  if (error) return <div className="empty error">{error}</div>;
  if (rev === 0) return <div className="empty">Раскладываем граф…</div>;

  return (
    <ReactFlow
      key={rev}
      nodes={nodes}
      edges={edges}
      onNodesChange={onNodesChange}
      onEdgesChange={onEdgesChange}
      nodeTypes={nodeTypes}
      nodesConnectable={false}
      onNodeClick={(_, n) => n.type === 'block' && onSelect(nodeOf(n))}
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
