import { useEffect, useState } from 'react';
import { Background, BackgroundVariant, Controls, MiniMap, ReactFlow, useEdgesState, useNodesInitialized, useNodesState, useReactFlow, useStore, type Edge, type Node, type NodeChange } from '@xyflow/react';
import type { Finding, Graph, GraphNode, NodeKind } from '../api/types';
import { PALETTE_MIME } from './BlockPalette';
import { BlockNode, FrameNode, kindColor } from './BlockNode';
import { edgePaint, layoutGraph, type Theme } from './layout';
import type { EdgeTone } from '../flow/methodFlow';
import type { OverviewMode } from '../flow/overviewViews';

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
  theme?: Theme;
  /** Вид блока из палитры брошен на узел схемы (палитра — только на блок-схеме метода). */
  onDropKind?: (kind: NodeKind, target: GraphNode) => void;
  /** Можно ли вставить после этого узла (insertAfter): иначе он не подсвечивается и бросить нельзя. */
  canDropOn?: (node: GraphNode) => boolean;
  /** Вид обзора: сценарий вызовов от main или разделы классов. */
  overviewMode?: OverviewMode;
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

/** Ниже этого масштаба код в блоках (11px) не читается — ни на ноутбуке, ни на проекторе. */
const READABLE_ZOOM = 0.75;
const PAD = 32;

/**
 * Начальный вид: граф целиком, если при этом он читается; иначе — читаемый масштаб и начало графа у верхнего края
 * (длинная блок-схема открывается со «Старта», остальное — прокруткой и мини-картой). Один раз на раскладку.
 */
function ReadableFit() {
  const flow = useReactFlow();
  const ready = useNodesInitialized();
  const width = useStore((s) => s.width);
  const height = useStore((s) => s.height);
  const [done, setDone] = useState(false);
  useEffect(() => {
    if (!ready || done || !width || !height) return;
    const b = flow.getNodesBounds(flow.getNodes());
    if (!b.width || !b.height) return;
    const fit = Math.min((width - 2 * PAD) / b.width, (height - 2 * PAD) / b.height, 1);
    if (fit >= READABLE_ZOOM) {
      void flow.fitView({ padding: 0.06, maxZoom: 1 });
    } else {
      const z = READABLE_ZOOM;
      const x = b.width * z <= width - 2 * PAD ? width / 2 - (b.x + b.width / 2) * z : PAD - b.x * z;
      void flow.setViewport({ x, y: PAD - b.y * z, zoom: z });
    }
    setDone(true);
  }, [ready, done, width, height, flow]);
  return null;
}

const NO_FINDINGS: Finding[] = [];

const nodeOf = (n: Node) => (n.data as { node: GraphNode }).node;

export function GraphCanvas({ graph, selectedIds, onSelect, onOpenMethod, focus, findings, theme = 'light', onDropKind, canDropOn, overviewMode = 'scenario' }: Props) {
  // Узлы живут в состоянии и принимают изменения от React Flow (размеры, выделение). Если отдавать новые
  // объекты на каждый рендер без onNodesChange, React Flow теряет замеры и на кадр прячет узлы —
  // второй клик двойного клика уходит в пустой холст, и метод не открывается.
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  /** Счётчик раскладок: новый граф → новый key → ReactFlow заново делает fitView. */
  const [rev, setRev] = useState(0);
  const [error, setError] = useState<string | null>(null);
  /** Блок под перетаскиваемым видом из палитры — подсвечен как место вставки. */
  const [dropId, setDropId] = useState<string | null>(null);

  /** Узел схемы под курсором, куда можно вставить: блок или рамка (тогда — после всей конструкции). */
  const dropTarget = (e: React.DragEvent): { id: string; node: GraphNode } | null => {
    const el = document.elementFromPoint(e.clientX, e.clientY)?.closest<HTMLElement>('.react-flow__node');
    const flowNode = el?.dataset.id ? nodes.find((n) => n.id === el.dataset.id) : undefined;
    if (!flowNode) return null;
    const node = nodeOf(flowNode);
    return canDropOn?.(node) ? { id: flowNode.id, node } : null;
  };

  // Подсветка места вставки — класс узла React Flow, раскладку не трогаем.
  useEffect(() => {
    setNodes((nds) => nds.map((n) => {
      const want = n.id === dropId ? 'drop-target' : undefined;
      return n.className === want ? n : { ...n, className: want };
    }));
  }, [dropId, rev, setNodes]);

  useEffect(() => {
    if (!graph) return;
    let cancelled = false;
    layoutGraph(graph, theme, overviewMode)
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
  }, [graph, overviewMode, setNodes, setEdges]);

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
        const opacity = hot ? 1 : selected.size ? 0.12 : 0.45;
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

  // Смена темы: перекрашиваем рёбра (цвет стрелки задаётся строкой), раскладку не трогаем.
  useEffect(() => {
    setEdges((es) =>
      es.map((e) => {
        const tone = (e.data as { tone?: EdgeTone } | undefined)?.tone;
        return tone ? { ...e, ...edgePaint(tone, theme, e) } : e;
      }),
    );
  }, [theme, setEdges]);

  if (error) return <div className="empty error">{error}</div>;
  if (rev === 0) return <div className="empty">Раскладываем граф…</div>;

  return (
    <ReactFlow
      key={rev}
      className={`level-${graph?.level ?? 'method'} ${graph?.level === 'overview' ? `overview-${overviewMode}` : ''}`}
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
      onDragOver={(e) => {
        if (!onDropKind || !e.dataTransfer.types.includes(PALETTE_MIME)) return;
        e.preventDefault();
        const target = dropTarget(e);
        e.dataTransfer.dropEffect = target ? 'copy' : 'none';
        setDropId(target?.id ?? null);
      }}
      onDragLeave={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as globalThis.Node | null)) setDropId(null);
      }}
      onDrop={(e) => {
        if (!onDropKind || !e.dataTransfer.types.includes(PALETTE_MIME)) return;
        e.preventDefault();
        setDropId(null);
        const target = dropTarget(e);
        if (target) onDropKind(e.dataTransfer.getData(PALETTE_MIME) as NodeKind, target.node);
      }}
      minZoom={0.1}
      colorMode={theme}
      proOptions={{ hideAttribution: false }}
    >
      <Background variant={BackgroundVariant.Dots} gap={20} size={1.4} color="var(--grid-dot)" />
      <Controls showInteractive={false} />
      <MiniMap
        pannable
        zoomable
        bgColor="var(--island)"
        maskColor="color-mix(in srgb, var(--app-bg) 70%, transparent)"
        maskStrokeColor="var(--accent)"
        maskStrokeWidth={1}
        // Рамки групп на мини-карте — только контур, иначе они закрашивают блоки внутри.
        nodeColor={(n) => (n.type === 'frame' ? 'transparent' : kindColor(nodeOf(n).kind))}
        nodeStrokeColor={(n) => (n.type === 'frame' ? 'var(--node-border)' : 'transparent')}
        nodeStrokeWidth={2}
        nodeBorderRadius={2}
      />
      <ReadableFit />
      <FocusNode focus={focus} />
    </ReactFlow>
  );
}
