// Правка на графе (POST /graph/ops, contracts/openapi.yaml GraphOpRequest): что можно делать с узлом,
// шаблоны вставки и как вернуться к тому же месту после операции. Без React — тестируется в node.
import type { Graph, GraphNode, NodeKind, SourceFile } from '../api/types';

export type EditOp = 'editNode' | 'insertAfter' | 'delete';

/** Операции над выделением из одного или нескольких блоков (DefaultGraphOperations.selection). */
export type SelectionOp = 'wrapIf' | 'wrapLoop' | 'extractMethod' | 'move' | 'delete';

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

/**
 * Номера строк нового текста, которых нет в старом (добавленные/изменённые), по LCS построчно.
 * Общие начало и конец отрезаются, поэтому на правке в одном месте файла это почти бесплатно.
 */
export function changedLineNumbers(oldText: string, newText: string): number[] {
  const a = oldText.split('\n');
  const b = newText.split('\n');
  let pre = 0;
  while (pre < a.length && pre < b.length && a[pre] === b[pre]) pre++;
  let suf = 0;
  while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] === b[b.length - 1 - suf]) suf++;
  const x = a.slice(pre, a.length - suf);
  const y = b.slice(pre, b.length - suf);
  const n = x.length;
  const m = y.length;
  // lcs[i][j] — длина LCS суффиксов x[i..], y[j..]
  const lcs = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = x[i] === y[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: number[] = [];
  let i = 0;
  let j = 0;
  while (j < m) {
    if (i < n && x[i] === y[j]) { i++; j++; }
    else if (i < n && lcs[i + 1][j] >= lcs[i][j + 1]) i++;
    else { out.push(pre + j + 1); j++; }
  }
  return out;
}

/**
 * Ядро выделяет только операторы (не start/end, не case/catch/finally — это части конструкций),
 * а на обзоре — члены класса (для move и delete). Смежность проверяет ядро и объясняет отказ.
 */
const NOT_STATEMENTS = new Set<NodeKind>(['start', 'end', 'case', 'catch', 'finally']);

export function selectionOps(nodes: GraphNode[], level: Graph['level']): SelectionOp[] {
  if (nodes.length === 0) return [];
  if (level === 'overview') {
    return nodes.every((n) => MEMBERS.has(n.kind)) ? ['move', 'delete'] : [];
  }
  if (nodes.some((n) => NOT_STATEMENTS.has(n.kind) || TYPES.has(n.kind) || MEMBERS.has(n.kind))) return [];
  return ['wrapIf', 'wrapLoop', 'extractMethod', 'move', 'delete'];
}

/** Выделение в порядке кода — так его поймёт пользователь в списке и так удобнее читать запрос. */
export function inCodeOrder(nodes: GraphNode[]): GraphNode[] {
  return [...nodes].sort((a, b) => (a.range?.startLine ?? 0) - (b.range?.startLine ?? 0) || (a.range?.startColumn ?? 0) - (b.range?.startColumn ?? 0));
}

export interface MoveTarget {
  id: string;
  label: string;
}

/**
 * Куда можно переместить выделение (по правилам insertAfter): любой блок, кроме конца метода,
 * самих выделенных и того, что лежит у них внутри. На обзоре — после другого члена класса.
 */
export function moveTargets(graph: Graph, selection: GraphNode[]): MoveTarget[] {
  const byId = new Map(graph.nodes.map((n) => [n.id, n]));
  const selected = new Set(selection.map((n) => n.id));
  const insideSelection = (n: GraphNode): boolean => {
    const seen = new Set<string>();
    let p = n.parent ? byId.get(n.parent) : undefined;
    while (p && !seen.has(p.id)) {
      if (selected.has(p.id)) return true;
      seen.add(p.id);
      p = p.parent ? byId.get(p.parent) : undefined;
    }
    return false;
  };
  const members = graph.level === 'overview';
  return inCodeOrder(graph.nodes)
    .filter((n) => !selected.has(n.id) && !insideSelection(n) && n.kind !== 'end')
    .filter((n) => (members ? MEMBERS.has(n.kind) : !TYPES.has(n.kind) && !MEMBERS.has(n.kind)))
    .map((n) => {
      const where = ['start', 'case', 'catch', 'finally'].includes(n.kind) ? 'в начало' : 'после';
      const text = n.kind === 'start' ? 'тела метода' : `«${n.code ?? n.label}»`;
      const line = n.range && n.kind !== 'start' ? ` — стр. ${n.range.startLine}` : '';
      return { id: n.id, label: `${where} ${text}${line}` };
    });
}

/** Имя нового метода для extractMethod — Java-идентификатор (ядро проверит ещё раз). */
export const isJavaIdentifier = (s: string) => /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(s.trim());
