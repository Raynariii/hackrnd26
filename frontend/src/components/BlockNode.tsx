import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { Finding, GraphNode } from '../api/types';
import { HANDLE, frameTitle, nodeShape, nodeSize, type Shape, type Size } from '../flow/methodFlow';

/**
 * Цвет вида узла — «карандаш» на чертеже: ветвления охрой, циклы бирюзой, исключения красным, остальное графитом.
 * Вид и так различает форма блока, цвет лишь выделяет управляющие конструкции. Значения — токены styles.css.
 */
const INK = '#18202b';
const INK_2 = '#465163';
const MUTED = '#667085';
const BRANCH = '#8a5300';
const LOOP = '#0b6b6b';
const ERROR = '#b42318';

export const KIND_COLORS: Partial<Record<GraphNode['kind'], string>> = {
  class: INK_2, interface: INK_2, enum: INK_2, record: INK_2,
  method: INK, constructor: INK, field: MUTED,
  start: INK, end: INK, variable: INK_2, operation: INK_2, call: INK_2, return: INK,
  condition: BRANCH, switch: BRANCH, case: BRANCH,
  loop: LOOP, break: MUTED, continue: MUTED,
  try: ERROR, catch: ERROR, finally: ERROR, throw: ERROR,
  raw: MUTED,
};

export const kindColor = (kind: GraphNode['kind']) => KIND_COLORS[kind] ?? MUTED;

const SEVERITY_ORDER: Finding['severity'][] = ['high', 'medium', 'low'];
const SEVERITY_LABEL: Record<Finding['severity'], string> = { high: 'высокая', medium: 'средняя', low: 'низкая' };

function worstSeverity(list: Finding[]): Finding['severity'] {
  return SEVERITY_ORDER.find((s) => list.some((f) => f.severity === s)) ?? 'high';
}

function findingsTitle(list: Finding[], count: number): string {
  if (!list.length) return `Проблем: ${count}`;
  return list
    .map((f) => `• ${f.title} (${SEVERITY_LABEL[f.severity]}${f.complexity ? `, ${f.complexity.current}` : ''})`)
    .join('\n');
}

/** Контур для фигур, которые не рисуются CSS-рамкой: ромб (условие) и шестиугольник (цикл). */
function ShapeOutline({ shape, size, color }: { shape: Shape; size: Size; color: string }) {
  const { width: w, height: h } = size;
  const s = 1; // отступ под толщину линии
  const points =
    shape === 'decision'
      ? `${w / 2},${s} ${w - s},${h / 2} ${w / 2},${h - s} ${s},${h / 2}`
      : `${h / 2},${s} ${w - h / 2},${s} ${w - s},${h / 2} ${w - h / 2},${h - s} ${h / 2},${h - s} ${s},${h / 2}`;
  return (
    <svg className="block-outline" width={w} height={h} aria-hidden>
      <polygon points={points} fill="var(--sheet)" stroke={color} strokeWidth={1} />
    </svg>
  );
}

export function BlockNode({ data }: NodeProps) {
  const { node, size: given, findings: list = [], selected = false } = data as {
    node: GraphNode;
    size?: Size;
    findings?: Finding[];
    /** Выделение задаёт App (может быть несколько блоков) — не флаг React Flow. */
    selected?: boolean;
  };
  const size = given ?? nodeSize(node.kind);
  const shape = nodeShape(node.kind);
  const color = kindColor(node.kind);
  // Находки сопоставляет фронт (id / meta.covers); node.findings ядро не заполняет — он только запасной вариант.
  const findings = list.length || (node.findings?.length ?? 0);
  const severity = worstSeverity(list);
  const outlined = shape === 'decision' || shape === 'loop';

  return (
    <div
      className={`block shape-${shape} ${selected ? 'selected' : ''} ${findings ? 'has-findings' : ''}`}
      style={{ width: size.width, height: size.height, borderColor: color, ['--kind' as string]: color }}
      title={node.code ?? node.label}
    >
      {outlined && <ShapeOutline shape={shape} size={size} color={color} />}
      <Handle type="target" id={HANDLE.in} position={Position.Top} />
      {/* У ромба и шестиугольника боковой угол — посередине высоты, у прямоугольников ручки разнесены. */}
      <Handle type="target" id={HANDLE.backIn} position={Position.Right} className="handle-side" style={{ top: outlined ? '50%' : '30%' }} />
      <div className="block-body">
        <div className="block-title" style={{ color }}>
          {node.label}
          {findings > 0 && (
            <span className={`block-warning sev-${severity}`} title={findingsTitle(list, findings)}>
              {findings > 1 ? findings : '!'}
            </span>
          )}
        </div>
        {node.code && <div className="block-code">{node.code}</div>}
      </div>
      <Handle type="source" id={HANDLE.out} position={Position.Bottom} />
      {shape === 'decision' && <Handle type="source" id={HANDLE.no} position={Position.Right} />}
      <Handle type="source" id={HANDLE.backOut} position={Position.Right} className="handle-side" style={{ top: outlined ? '50%' : '70%' }} />
      {shape === 'loop' && <Handle type="source" id={HANDLE.exit} position={Position.Left} className="handle-side" />}
    </div>
  );
}

/** Рамка группы: тело цикла, try/catch/finally, switch/case. Размер задаёт раскладка. */
export function FrameNode({ data }: NodeProps) {
  const node = (data as { node: GraphNode }).node;
  const color = kindColor(node.kind);
  return (
    <div className="frame" style={{ borderColor: color, ['--kind' as string]: color }}>
      <div className="frame-title" style={{ color }}>{frameTitle(node)}</div>
    </div>
  );
}
