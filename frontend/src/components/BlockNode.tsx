import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { GraphNode } from '../api/types';
import { HANDLE, frameTitle, nodeShape, nodeSize, type Shape, type Size } from '../flow/methodFlow';

export const KIND_COLORS: Partial<Record<GraphNode['kind'], string>> = {
  class: '#64748b', interface: '#64748b', enum: '#64748b', record: '#64748b',
  method: '#8b5cf6', constructor: '#8b5cf6', field: '#0ea5e9',
  start: '#8b5cf6', end: '#8b5cf6', variable: '#3b82f6', condition: '#f59e0b', loop: '#a855f7',
  call: '#06b6d4', operation: '#3b82f6', return: '#22c55e', throw: '#ef4444',
  try: '#ec4899', catch: '#ec4899', finally: '#ec4899', switch: '#f59e0b', case: '#f59e0b',
  break: '#94a3b8', continue: '#94a3b8', raw: '#6b7280',
};

export const kindColor = (kind: GraphNode['kind']) => KIND_COLORS[kind] ?? '#6b7280';

/** Контур для фигур, которые не рисуются CSS-рамкой: ромб (условие) и шестиугольник (цикл). */
function ShapeOutline({ shape, size, color }: { shape: Shape; size: Size; color: string }) {
  const { width: w, height: h } = size;
  const s = 1.5; // отступ под толщину линии
  const points =
    shape === 'decision'
      ? `${w / 2},${s} ${w - s},${h / 2} ${w / 2},${h - s} ${s},${h / 2}`
      : `${h / 2},${s} ${w - h / 2},${s} ${w - s},${h / 2} ${w - h / 2},${h - s} ${h / 2},${h - s} ${s},${h / 2}`;
  return (
    <svg className="block-outline" width={w} height={h} aria-hidden>
      <polygon points={points} fill="var(--panel)" stroke={color} strokeWidth={1.5} />
    </svg>
  );
}

export function BlockNode({ data, selected }: NodeProps) {
  const { node, size: given } = data as { node: GraphNode; size?: Size };
  const size = given ?? nodeSize(node.kind);
  const shape = nodeShape(node.kind);
  const color = kindColor(node.kind);
  const findings = node.findings?.length ?? 0;
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
            <span className="block-warning" title={`Проблем: ${findings}`}>!</span>
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
