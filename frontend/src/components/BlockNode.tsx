import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { Finding, GraphNode } from '../api/types';
import { HANDLE, frameTitle, nodeShape, nodeSize, type Shape, type Size } from '../flow/methodFlow';

/**
 * Цвет вида узла — CSS-переменные темы (styles.css): ветвления янтарём, циклы бирюзой, исключения красным,
 * остальное нейтрально. Вид и так различает форма блока, цвет лишь выделяет управляющие конструкции.
 */
const NEUTRAL = 'var(--node-border)';
const TYPE = 'var(--k-type)';
const BRANCH = 'var(--k-branch)';
const LOOP = 'var(--k-loop)';
const ERROR = 'var(--k-error)';

const TYPE_KINDS = new Set<GraphNode['kind']>(['class', 'interface', 'enum', 'record']);

/** Map, а не объект: у объекта ключ 'constructor' вернул бы Object.prototype.constructor. */
export const KIND_COLORS = new Map<GraphNode['kind'], string>([
  ['class', TYPE], ['interface', TYPE], ['enum', TYPE], ['record', TYPE],
  ['condition', BRANCH], ['switch', BRANCH], ['case', BRANCH],
  ['loop', LOOP],
  ['try', ERROR], ['catch', ERROR], ['finally', ERROR], ['throw', ERROR],
]);

/** Цвет рамки и заголовка блока. Нейтральные виды — цвет рамки по умолчанию. */
export const kindColor = (kind: GraphNode['kind']) => KIND_COLORS.get(kind) ?? NEUTRAL;
/** Цвет подписи вида: у нейтральных — вторичный текст, у цветных — их цвет. */
const titleColor = (kind: GraphNode['kind']) => KIND_COLORS.get(kind) ?? 'var(--node-title)';

const SEVERITY_ORDER: Finding['severity'][] = ['high', 'medium', 'low'];
const SEVERITY_LABEL: Record<Finding['severity'], string> = { high: 'критично', medium: 'важно', low: 'мелочь' };

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
      <polygon points={points} style={{ fill: 'var(--node-bg)', stroke: color, strokeWidth: 1 }} />
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
      // Рамку красят только управляющие конструкции; у типов и операторов — нейтральная рамка темы.
      style={{ width: size.width, height: size.height, borderColor: KIND_COLORS.has(node.kind) && !TYPE_KINDS.has(node.kind) ? color : undefined, ['--kind' as string]: color }}
      title={node.code ?? node.label}
    >
      {outlined && <ShapeOutline shape={shape} size={size} color={color} />}
      <Handle type="target" id={HANDLE.in} position={Position.Top} />
      {/* У ромба и шестиугольника боковой угол — посередине высоты, у прямоугольников ручки разнесены. */}
      <Handle type="target" id={HANDLE.backIn} position={Position.Right} className="handle-side" style={{ top: outlined ? '50%' : '30%' }} />
      <div className="block-body">
        <div className="block-title" style={{ color: titleColor(node.kind) }}>
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
      <Handle type="target" id={HANDLE.west} position={Position.Left} className="handle-side" />
      <Handle type="source" id={HANDLE.east} position={Position.Right} className="handle-side" />
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
    <div className={`frame ${TYPE_KINDS.has(node.kind) ? 'frame-type' : ''}`} style={{ ['--kind' as string]: color }}>
      <div className="frame-title" style={{ color: titleColor(node.kind) }}>{frameTitle(node)}</div>
    </div>
  );
}
