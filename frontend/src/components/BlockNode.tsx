import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { GraphNode } from '../api/types';

const COLORS: Partial<Record<GraphNode['kind'], string>> = {
  class: '#64748b', method: '#8b5cf6', constructor: '#8b5cf6', field: '#0ea5e9',
  start: '#8b5cf6', variable: '#3b82f6', condition: '#f59e0b', loop: '#a855f7',
  call: '#3b82f6', operation: '#3b82f6', return: '#22c55e', throw: '#ef4444',
  try: '#ec4899', catch: '#ec4899', finally: '#ec4899', switch: '#f59e0b', raw: '#6b7280',
};

export function BlockNode({ data, selected }: NodeProps) {
  const node = (data as { node: GraphNode }).node;
  const color = COLORS[node.kind] ?? '#6b7280';
  const hasFindings = (node.findings?.length ?? 0) > 0;
  return (
    <div className={`block ${selected ? 'selected' : ''}`} style={{ borderColor: color }}>
      <Handle type="target" position={Position.Top} />
      <div className="block-title" style={{ color }}>
        {node.label}
        {hasFindings && <span className="block-warning" title="Есть проблемы">!</span>}
      </div>
      {node.code && <div className="block-code">{node.code}</div>}
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}
