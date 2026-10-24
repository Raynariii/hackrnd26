// Правка на графе (POST /graph/ops, contracts/openapi.yaml GraphOpRequest): что можно делать с узлом,
// шаблоны вставки и как вернуться к тому же месту после операции. Без React — тестируется в node.
import type { Graph, GraphNode, NodeKind, SourceFile } from '../api/types';

export type EditOp = 'editNode' | 'insertAfter' | 'delete';

const MEMBERS = new Set<NodeKind>(['method', 'constructor', 'field']);
const TYPES = new Set<NodeKind>(['class', 'interface', 'enum', 'record']);

/**
 * Какие операции ядро принимает для узла (DefaultGraphOperations): start/end не правятся и не удаляются,
 * после end вставлять нельзя; у finally нет заголовка; на обзоре правим объявления членов,
 * вставка — только в блок-схеме метода.
 */
export function allowedOps(node: GraphNode, level: Graph['level']): Set<EditOp> {
  const ops = new Set<EditOp>();
  if (level === 'overview') {
    if (MEMBERS.has(node.kind)) ops.add('editNode').add('delete');
    return ops;
  }
  if (TYPES.has(node.kind) || MEMBERS.has(node.kind)) return ops;
  if (node.kind === 'end') return ops;
  ops.add('insertAfter');
  if (node.kind === 'start') return ops;
  ops.add('delete');
  if (node.kind !== 'finally') ops.add('editNode');
  return ops;
}

/** Подсказка, что именно меняет editNode у этого узла (ядро меняет у контейнеров только заголовок). */
export function editHint(node: GraphNode): string {
  switch (node.kind) {
    case 'condition': return 'Условие: только выражение в скобках, тело ветвей сохранится';
    case 'loop': return 'Заголовок цикла: for (…) / while (…), тело сохранится';
    case 'switch': return 'Выражение switch, ветки сохранятся';
    case 'case': return 'Метки case';
    case 'catch': return 'Параметр catch, например IOException e';
    case 'try': return 'Ресурсы try (…), тело сохранится';
    case 'method':
    case 'constructor': return 'Объявление (сигнатура)';
    default: return 'Оператор целиком; можно несколько через ;';
  }
}

export interface InsertKind {
  kind: NodeKind;
  label: string;
  /** Пример сокращённого кода: ядро достраивает его по шаблону (DefaultGraphOperations.template). */
  placeholder: string;
  /** Код можно не вводить (break;, continue;, return;). */
  optionalCode?: boolean;
}

export const INSERT_KINDS: InsertKind[] = [
  { kind: 'operation', label: 'Операция', placeholder: 'total += x' },
  { kind: 'variable', label: 'Переменная', placeholder: 'int count = 0' },
  { kind: 'call', label: 'Вызов', placeholder: 'System.out.println(x)' },
  { kind: 'condition', label: 'Условие', placeholder: 'x > 0' },
  { kind: 'loop', label: 'Цикл', placeholder: 'int i = 0; i < n; i++   или   String s : items   или   x > 0' },
  { kind: 'return', label: 'Возврат', placeholder: 'result', optionalCode: true },
  { kind: 'throw', label: 'Исключение', placeholder: 'new IllegalArgumentException("…")', optionalCode: true },
  { kind: 'try', label: 'try / catch', placeholder: 'try (var in = open())', optionalCode: true },
  { kind: 'break', label: 'break', placeholder: 'break', optionalCode: true },
  { kind: 'continue', label: 'continue', placeholder: 'continue', optionalCode: true },
];

/**
 * Id пересчитываются после каждой правки («a» + порядковый номер обхода AST), поэтому открытый метод
 * ищем в новом обзоре заново: тот же файл, имя и сигнатура; если сигнатуру правили — по одному имени.
 */
export function findMethodAgain(oldOverview: Graph, oldMethodId: string, newOverview: Graph): string | null {
  const old = oldOverview.nodes.find((n) => n.id === oldMethodId);
  if (!old) return null;
  const sameKind = newOverview.nodes.filter(
    (n) => n.kind === old.kind && n.label === old.label && n.range?.file === old.range?.file,
  );
  const exact = sameKind.find((n) => n.code === old.code);
  if (exact) return exact.id;
  return sameKind.length === 1 ? sameKind[0].id : null;
}

/** Первая изменившаяся строка (по новому тексту) — туда ставим выделение и курсор после операции. */
export function firstChangedLine(before: SourceFile[], after: SourceFile[]): { file: string; line: number } | null {
  for (const f of after) {
    const old = before.find((b) => b.path === f.path);
    if (!old) return { file: f.path, line: 1 };
    if (old.content === f.content) continue;
    const a = old.content.split('\n');
    const b = f.content.split('\n');
    for (let i = 0; i < b.length; i++) if (a[i] !== b[i]) return { file: f.path, line: i + 1 };
    return { file: f.path, line: b.length };
  }
  return null;
}
