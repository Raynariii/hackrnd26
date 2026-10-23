// Чистая логика блок-схемы: граф из контракта → модель для холста (группы, рёбра, ручки) → вход для elkjs.
// Без React и без elkjs, чтобы тестироваться в node (`npm test`). Импорты здесь — только `import type`.
import type { EdgeKind, Graph, GraphEdge, GraphNode, NodeKind } from '../api/types';

export interface Size {
  width: number;
  height: number;
}

const DEFAULT_SIZE: Size = { width: 260, height: 64 };
// Map, а не объект: в NodeKind есть 'constructor', и obj['constructor'] вернул бы Object.prototype.constructor.
const SIZES = new Map<NodeKind, Size>([
  ['start', { width: 240, height: 52 }],
  ['end', { width: 160, height: 44 }],
  ['return', { width: 240, height: 52 }],
  ['break', { width: 160, height: 44 }],
  ['continue', { width: 160, height: 44 }],
  ['condition', { width: 280, height: 104 }],
  ['switch', { width: 280, height: 104 }],
  ['loop', { width: 280, height: 72 }],
  ['case', { width: 220, height: 52 }],
  ['raw', { width: 300, height: 92 }],
]);

export function nodeSize(kind: NodeKind): Size {
  return SIZES.get(kind) ?? DEFAULT_SIZE;
}

/** Форма блока по ГОСТ-подобной нотации блок-схем. */
export type Shape = 'terminal' | 'decision' | 'loop' | 'process' | 'call' | 'raw';

const SHAPES = new Map<NodeKind, Shape>([
  ['start', 'terminal'],
  ['end', 'terminal'],
  ['return', 'terminal'],
  ['break', 'terminal'],
  ['continue', 'terminal'],
  ['condition', 'decision'],
  ['switch', 'decision'],
  ['loop', 'loop'],
  ['call', 'call'],
  ['raw', 'raw'],
]);

export function nodeShape(kind: NodeKind): Shape {
  return SHAPES.get(kind) ?? 'process';
}

/** Заголовок рамки-группы для узла-контейнера (цикл, try, catch, switch…). */
const FRAME_TITLES = new Map<NodeKind, string>([
  ['loop', 'Тело цикла'],
  ['try', 'Блок try'],
  ['catch', 'Блок catch'],
  ['finally', 'Блок finally'],
  ['switch', 'Блок switch'],
  ['case', 'Ветка case'],
  ['condition', 'Ветки условия'],
]);

export function frameTitle(node: GraphNode): string {
  return FRAME_TITLES.get(node.kind) ?? node.label;
}

const DEFAULT_EDGE_LABELS = new Map<EdgeKind, string>([
  ['true', 'Да'],
  ['false', 'Нет'],
  ['exception', 'исключение'],
]);

export const FRAME_PREFIX = 'frame:';
export const frameId = (nodeId: string) => FRAME_PREFIX + nodeId;

/** Ручки блока (id совпадают с <Handle id> в BlockNode). */
export const HANDLE = {
  in: 'in',
  out: 'out',
  no: 'no',
  backIn: 'back-in',
  backOut: 'back-out',
} as const;

export type EdgeTone = 'yes' | 'no' | 'back' | 'exception' | 'call' | 'contains' | 'default';

export interface FlowFrame {
  type: 'frame';
  id: string;
  /** Узел-заголовок группы (цикл, try…). */
  node: GraphNode;
  parent: string | null;
}

export interface FlowBlock {
  type: 'block';
  id: string;
  node: GraphNode;
  parent: string | null;
  size: Size;
}

export type FlowItem = FlowFrame | FlowBlock;

export interface FlowEdge {
  edge: GraphEdge;
  /** Обратное ребро (возврат к заголовку цикла): не участвует в раскладке, рисуется сбоку. */
  back: boolean;
  label: string | null;
  sourceHandle: string;
  targetHandle: string;
  tone: EdgeTone;
}

export interface FlowModel {
  /** Сначала рамки (от внешних к внутренним), затем блоки — так требует React Flow для parentId. */
  items: FlowItem[];
  edges: FlowEdge[];
  /** id элемента → id рамки-родителя (null — корень). */
  parentOf: Map<string, string | null>;
}

export interface BuildOptions {
  /** Строить рамки по полю parent. Для обзора выключено: там вложенность уже показана рёбрами contains. */
  grouping: boolean;
}

export function buildFlowModel(graph: Graph, options: BuildOptions): FlowModel {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const graphParent = (n: GraphNode): string | null =>
    n.parent && n.parent !== n.id && byId.has(n.parent) ? n.parent : null;

  // Цепочка предков по полю parent; защищаемся от циклов в данных.
  const ancestors = new Map<string, string[]>();
  const chainOf = (id: string): string[] => {
    const cached = ancestors.get(id);
    if (cached) return cached;
    const chain: string[] = [];
    const seen = new Set([id]);
    let cur = byId.get(id);
    while (cur) {
      const p = graphParent(cur);
      if (!p || seen.has(p)) break;
      chain.push(p);
      seen.add(p);
      cur = byId.get(p);
    }
    ancestors.set(id, chain);
    return chain;
  };

  const containers = new Set<string>();
  if (options.grouping) {
    for (const n of graph.nodes) {
      const p = graphParent(n);
      if (p && !chainOf(p).includes(n.id)) containers.add(p);
    }
  }

  const parentOf = new Map<string, string | null>();
  const firstContainer = (chain: string[]) => chain.find((id) => containers.has(id)) ?? null;

  const frames: { frame: FlowFrame; depth: number }[] = [];
  for (const id of containers) {
    const node = byId.get(id)!;
    const outer = firstContainer(chainOf(id));
    const parent = outer ? frameId(outer) : null;
    const fid = frameId(id);
    parentOf.set(fid, parent);
    frames.push({ frame: { type: 'frame', id: fid, node, parent }, depth: chainOf(id).length });
  }
  frames.sort((a, b) => a.depth - b.depth);

  const blocks: FlowBlock[] = graph.nodes.map((node) => {
    let parent: string | null;
    if (containers.has(node.id)) {
      parent = frameId(node.id); // заголовок группы стоит внутри своей рамки, первым
    } else {
      const c = firstContainer(chainOf(node.id));
      parent = c ? frameId(c) : null;
    }
    parentOf.set(node.id, parent);
    return { type: 'block', id: node.id, node, parent, size: nodeSize(node.kind) };
  });

  const isBack = (e: GraphEdge): boolean => {
    if (e.kind === 'loopBack' || e.kind === 'continue') return true;
    // Переход изнутри тела к заголовку своего же контейнера (например, «Нет» внутреннего условия → цикл).
    return e.from !== e.to && containers.has(e.to) && chainOf(e.from).includes(e.to);
  };

  const edges: FlowEdge[] = graph.edges
    .filter((e) => byId.has(e.from) && byId.has(e.to))
    .map((e) => {
      const back = isBack(e);
      const source = byId.get(e.from)!;
      const sourceHandle = back
        ? HANDLE.backOut
        : e.kind === 'false' && nodeShape(source.kind) === 'decision'
          ? HANDLE.no
          : HANDLE.out;
      return {
        edge: e,
        back,
        label: e.label ?? DEFAULT_EDGE_LABELS.get(e.kind) ?? null,
        sourceHandle,
        targetHandle: back ? HANDLE.backIn : HANDLE.in,
        tone: edgeTone(e.kind, back),
      };
    });

  return { items: [...frames.map((f) => f.frame), ...blocks], edges, parentOf };
}

function edgeTone(kind: EdgeKind, back: boolean): EdgeTone {
  if (back) return 'back';
  switch (kind) {
    case 'true': return 'yes';
    case 'false': return 'no';
    case 'exception': return 'exception';
    case 'call': return 'call';
    case 'contains': return 'contains';
    default: return 'default';
  }
}

// ---------- elkjs ----------

/** Минимальная форма графа elkjs — описана здесь, чтобы модуль не зависел от elkjs. */
export interface ElkNodeLike {
  id: string;
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  layoutOptions?: Record<string, string>;
  children?: ElkNodeLike[];
  edges?: { id: string; sources: string[]; targets: string[] }[];
}

export const FRAME_PADDING = { top: 36, left: 16, bottom: 16, right: 40 };

const ROOT_OPTIONS: Record<string, string> = {
  'elk.algorithm': 'layered',
  'elk.direction': 'DOWN',
  'elk.hierarchyHandling': 'INCLUDE_CHILDREN',
  'elk.spacing.nodeNode': '36',
  'elk.layered.spacing.nodeNodeBetweenLayers': '44',
  'elk.layered.spacing.edgeNodeBetweenLayers': '20',
  'elk.layered.nodePlacement.strategy': 'BRANDES_KOEPF',
  'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
};

const FRAME_OPTIONS: Record<string, string> = {
  'elk.padding': `[top=${FRAME_PADDING.top},left=${FRAME_PADDING.left},bottom=${FRAME_PADDING.bottom},right=${FRAME_PADDING.right}]`,
};

/** Иерархический граф для elkjs: рамки — составные узлы, ребро кладётся в общий контейнер концов. */
export function toElkGraph(model: FlowModel): ElkNodeLike {
  const root: ElkNodeLike = { id: 'root', layoutOptions: ROOT_OPTIONS, children: [], edges: [] };
  const elkById = new Map<string, ElkNodeLike>([['root', root]]);

  for (const item of model.items) {
    const elkNode: ElkNodeLike =
      item.type === 'frame'
        ? { id: item.id, layoutOptions: FRAME_OPTIONS, children: [], edges: [] }
        : { id: item.id, width: item.size.width, height: item.size.height };
    elkById.set(item.id, elkNode);
    elkById.get(item.parent ?? 'root')!.children!.push(elkNode);
  }

  for (const fe of model.edges) {
    if (fe.back) continue;
    const container = commonContainer(model.parentOf, fe.edge.from, fe.edge.to);
    elkById.get(container)!.edges!.push({ id: fe.edge.id, sources: [fe.edge.from], targets: [fe.edge.to] });
  }
  return root;
}

function containerChain(parentOf: Map<string, string | null>, id: string): string[] {
  const chain: string[] = [];
  let cur = parentOf.get(id) ?? null;
  while (cur) {
    chain.push(cur);
    cur = parentOf.get(cur) ?? null;
  }
  chain.push('root');
  return chain;
}

/** Ближайшая рамка, содержащая оба узла (или 'root'). */
export function commonContainer(parentOf: Map<string, string | null>, a: string, b: string): string {
  const chainB = new Set(containerChain(parentOf, b));
  return containerChain(parentOf, a).find((c) => chainB.has(c)) ?? 'root';
}

export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Координаты из результата elkjs. Как и в React Flow, x/y дочерних узлов — относительно родителя. */
export function collectBoxes(layout: ElkNodeLike): Map<string, Box> {
  const boxes = new Map<string, Box>();
  const walk = (n: ElkNodeLike) => {
    for (const c of n.children ?? []) {
      boxes.set(c.id, { x: c.x ?? 0, y: c.y ?? 0, width: c.width ?? 0, height: c.height ?? 0 });
      walk(c);
    }
  };
  walk(layout);
  return boxes;
}
