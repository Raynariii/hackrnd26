import { useEffect, useState } from 'react';
import { Background, Controls, MiniMap, ReactFlow, useEdgesState, useNodesState, useReactFlow, type Edge, type Node, type NodeChange } from '@xyflow/react';
import type { Finding, Graph, GraphNode } from '../api/types';
import { BlockNode, FrameNode, kindColor } from './BlockNode';
import { layoutGraph } from './layout';

const nodeTypes = { block: BlockNode, frame: FrameNode };

interface Props {
  graph: Graph | null;
  /** Выделение задаёт App (клик, строка кода, находка); собственный выбор React Flow игнорируется. */
  selectedIds: string[];
  /** additive — клик с Shift/Ctrl/Cmd: добавить блок к выделению или убрать из него. */
  onSelect: (node: GraphNode, additive: boolean) => void;
  onOpenMethod: (node: GraphNode) => void;
  /** Показать узел на холсте (выбор пришёл из кода или из списка проблем). seq — чтобы повторный запрос тоже срабатывал. */
  focus?: { id: string; seq: number } | null;
  /** Находки анализатора, разложенные по блокам текущего графа (sourceMap.findingsByNode). */
  findings?: Map<string, Finding[]>;
}

/** Плавно центрирует холст на узле, сохраняя масштаб. Рендерится внутри ReactFlow ради useReactFlow. */
function FocusNode({ focus }: { focus: Props['focus'] }) {
  const flow = useReactFlow();
  useEffect(() => {
    if (!focus) return;
    let frame = 0;
    let tries = 0;
    const center = () => {
      const node = flow.getInternalNode(focus.id);
      const w = node?.measured.width;
      const h = node?.measured.height;
      if (!node || !w || !h) {
        // Сразу после новой раскладки узлы ещё не измерены — ждём пару кадров.
        if (tries++ < 30) frame = requestAnimationFrame(center);
        return;
      }
      const { x, y } = node.internals.positionAbsolute;
      flow.setCenter(x + w / 2, y + h / 2, { zoom: flow.getZoom(), duration: 300 });
    };
    center();
    return () => cancelAnimationFrame(frame);
  }, [focus, flow]);
  return null;
}

const NO_FINDINGS: Finding[] = [];

const nodeOf = (n: Node) => (n.data as { node: GraphNode }).node;

export function GraphCanvas({ graph, selectedIds, onSelect, onOpenMethod, focus, findings }: Props) {
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

  const selectedKey = selectedIds.join('|');
  const selected = new Set(selectedIds);
  // Выделение задаёт App (клик по узлу, находке, строке кода): меняем только узлы, у которых оно поменялось.
  useEffect(() => {
    setNodes((nds) =>
      nds.map((n) => {
        if (n.type !== 'block') return n;
        const want = selected.has(n.id);
        const data = n.data as { selected?: boolean };
        return Boolean(data.selected) === want ? n : { ...n, data: { ...n.data, selected: want } };
      }),
    );
  }, [selectedKey, rev, setNodes]);

  // Вызовы на обзоре: рёбра выделенных методов — яркие и поверх, остальные приглушены.
  useEffect(() => {
    setEdges((es) =>
      es.map((e) => {
        const data = e.data as { floating?: boolean } | undefined;
        if (!data?.floating) return e;
        const hot = selected.has(e.source) || selected.has(e.target);
        const opacity = hot ? 1 : selected.size ? 0.12 : 0.35;
        return e.style?.opacity === opacity ? e : { ...e, zIndex: hot ? 5 : 1, animated: hot, style: { ...e.style, opacity } };
      }),
    );
  }, [selectedKey, rev, setEdges]);

  // Маркеры находок: диагностика приходит отдельным запросом, раскладку из-за неё не пересчитываем.
  useEffect(() => {
    setNodes((nds) =>
      nds.map((n) => {
        if (n.type !== 'block') return n;
        const list = findings?.get(n.id) ?? NO_FINDINGS;
        const data = n.data as { findings?: Finding[] };
        return (data.findings ?? NO_FINDINGS) === list ? n : { ...n, data: { ...n.data, findings: list } };
      }),
    );
  }, [findings, rev, setNodes]);

  if (error) return <div className="empty error">{error}</div>;
  if (rev === 0) return <div className="empty">Раскладываем граф…</div>;

  return (
    <ReactFlow
      key={rev}
      nodes={nodes}
      edges={edges}
      // Выделение — только из App и только через data.selected: собственный выбор React Flow выключен,
      // иначе его «один клик — один узел» перебивает выделение нескольких блоков.
      elementsSelectable={false}
      onNodesChange={(changes: NodeChange[]) => onNodesChange(changes.filter((c) => c.type !== 'select'))}
      onEdgesChange={onEdgesChange}
      nodeTypes={nodeTypes}
      nodesConnectable={false}
      onNodeClick={(e, n) => n.type === 'block' && onSelect(nodeOf(n), e.shiftKey || e.ctrlKey || e.metaKey)}
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
      <MiniMap
        pannable
        zoomable
        // Рамки групп на мини-карте — только контур, иначе они закрашивают блоки внутри.
        nodeColor={(n) => (n.type === 'frame' ? 'transparent' : kindColor(nodeOf(n).kind))}
        nodeStrokeColor={(n) => (n.type === 'frame' ? kindColor(nodeOf(n).kind) : 'transparent')}
        nodeStrokeWidth={3}
      />
      <FocusNode focus={focus} />
    </ReactFlow>
  );
}
