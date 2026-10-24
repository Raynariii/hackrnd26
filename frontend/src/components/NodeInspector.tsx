import { useEffect, useState } from 'react';
import type { Graph, GraphNode, GraphOpRequest, NodeKind } from '../api/types';
import { allowedOps, editHint, inCodeOrder, INSERT_KINDS, isJavaIdentifier, moveTargets, selectionOps } from '../flow/edit';

type Mode = 'view' | 'edit' | 'insert' | 'delete' | 'wrapIf' | 'wrapLoop' | 'extractMethod' | 'move';

interface Props {
  /** Выделенные блоки; последний — основной. */
  selection: GraphNode[];
  graph: Graph;
  /** Выполнить операцию; вернуть текст ошибки для пользователя или null при успехе. */
  onOp: (req: Omit<GraphOpRequest, 'version'>) => Promise<string | null>;
}

const CONTAINERS = ['loop', 'condition', 'try', 'switch', 'catch', 'case'];

/** Выделенные узлы и правка на графе (POST /graph/ops): над одним блоком и над несколькими подряд идущими. */
export function NodeInspector({ selection, graph, onOp }: Props) {
  const node = selection.at(-1)!;
  const many = selection.length > 1;
  const ordered = inCodeOrder(selection);
  const single = many ? new Set() : allowedOps(node, graph.level);
  const multi = selectionOps(selection, graph.level);
  const targets = multi.includes('move') ? moveTargets(graph, selection) : [];

  const [mode, setMode] = useState<Mode>('view');
  const [code, setCode] = useState('');
  const [kind, setKind] = useState<NodeKind>('operation');
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Другое выделение — сбрасываем незавершённую правку.
  const key = selection.map((n) => n.id).join('|');
  useEffect(() => {
    setMode('view');
    setError(null);
  }, [key]);

  const open = (m: Mode) => {
    setError(null);
    setMode(m);
    setCode(m === 'edit' ? node.code ?? '' : '');
    if (m === 'move') setTarget(targets[0]?.id ?? '');
  };

  const submit = async (req: Omit<GraphOpRequest, 'version'>) => {
    setBusy(true);
    setError(null);
    const err = await onOp(req);
    setBusy(false);
    if (err) setError(err);
    else setMode('view');
  };

  const ids = ordered.map((n) => n.id);
  /** Один блок — nodeId (как в контракте для одиночных операций), несколько — nodeIds. */
  const targetOf = () => (many ? { nodeIds: ids } : { nodeId: node.id });

  const insertKind = INSERT_KINDS.find((k) => k.kind === kind)!;
  const actions = (
    <>
      {single.has('editNode') && <button onClick={() => open('edit')}>Изменить</button>}
      {single.has('insertAfter') && <button onClick={() => open('insert')}>Вставить после</button>}
      {multi.includes('wrapIf') && <button onClick={() => open('wrapIf')}>Обернуть в if</button>}
      {multi.includes('wrapLoop') && <button onClick={() => open('wrapLoop')}>Обернуть в цикл</button>}
      {multi.includes('extractMethod') && <button onClick={() => open('extractMethod')}>Вынести в метод</button>}
      {multi.includes('move') && targets.length > 0 && <button onClick={() => open('move')}>Переместить</button>}
      {(single.has('delete') || multi.includes('delete')) && <button className="danger" onClick={() => open('delete')}>Удалить</button>}
    </>
  );
  const hasActions = single.size > 0 || multi.length > 0;

  const form = (title: string, body: React.ReactNode, submitLabel: string, canSubmit: boolean, req: () => Omit<GraphOpRequest, 'version'>) => (
    <form className="inspector-form" onSubmit={(e) => { e.preventDefault(); if (canSubmit) void submit(req()); }}>
      <label className="muted">{title}</label>
      {body}
      <div className="inspector-actions">
        <button type="submit" className="primary" disabled={busy || !canSubmit}>{busy ? 'Применяем…' : submitLabel}</button>
        <button type="button" onClick={() => setMode('view')} disabled={busy}>Отмена</button>
      </div>
    </form>
  );

  return (
    <div className="inspector">
      <h3>{many ? `Выделено блоков: ${selection.length}` : 'Узел'}</h3>
      {many ? (
        <ol className="inspector-list">
          {ordered.map((n) => (
            <li key={n.id}><span>{n.label}</span> <code>{n.code ?? ''}</code></li>
          ))}
        </ol>
      ) : (
        <>
          <div>{node.label} <span className="muted">({node.kind})</span></div>
          {node.code && <code className="inspector-code">{node.code}</code>}
          {node.range && <div className="muted">строки {node.range.startLine}{node.range.endLine !== node.range.startLine ? `–${node.range.endLine}` : ''}</div>}
        </>
      )}

      {mode === 'view' && hasActions && <div className="inspector-actions">{actions}</div>}
      {mode === 'view' && !hasActions && (
        <div className="muted inspector-note">
          {many
            ? 'Над выделением можно работать, только если это операторы (не начало/конец, не case/catch/finally)'
            : node.kind === 'end' ? 'Конец метода не редактируется'
            : graph.level === 'overview' ? 'Откройте метод двойным кликом, чтобы править блоки' : 'Этот узел не редактируется'}
        </div>
      )}

      {mode === 'edit' && form(editHint(node),
        <textarea value={code} onChange={(e) => setCode(e.target.value)} rows={3} spellCheck={false} autoFocus
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) e.currentTarget.form?.requestSubmit(); }} />,
        'Применить', !!code.trim() && code !== node.code, () => ({ op: 'editNode', nodeId: node.id, code }))}

      {mode === 'insert' && form('Вид блока',
        <>
          <div className="kind-chips" role="radiogroup" aria-label="Вид блока">
            {INSERT_KINDS.map((k) => (
              <button type="button" key={k.kind} role="radio" aria-checked={kind === k.kind}
                className={`chip kind-${k.kind} ${kind === k.kind ? 'active' : ''}`} onClick={() => setKind(k.kind)}>
                {k.label}
              </button>
            ))}
          </div>
          <input value={code} onChange={(e) => setCode(e.target.value)} placeholder={insertKind.placeholder} spellCheck={false} autoFocus />
          <label className="muted">
            {['start', 'case', 'catch', 'finally'].includes(node.kind) ? 'Вставится в начало тела'
              : ['loop', 'condition', 'try', 'switch'].includes(node.kind) ? 'Вставится после всей конструкции' : 'Вставится следующим оператором'}
            {insertKind.optionalCode && ' · код можно не вводить'}
          </label>
        </>,
        'Вставить', !!insertKind.optionalCode || !!code.trim(), () => ({ op: 'insertAfter', nodeId: node.id, kind, code: code.trim() }))}

      {mode === 'wrapIf' && form('Условие, например x > 0',
        <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="x > 0" spellCheck={false} autoFocus />,
        'Обернуть', !!code.trim(), () => ({ op: 'wrapIf', ...targetOf(), code: code.trim() }))}

      {mode === 'wrapLoop' && form('Заголовок цикла',
        <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="int i = 0; i < n; i++   или   x : items   или   i < n" spellCheck={false} autoFocus />,
        'Обернуть', !!code.trim(), () => ({ op: 'wrapLoop', ...targetOf(), code: code.trim() }))}

      {mode === 'extractMethod' && form('Имя нового private-метода; параметры и результат ядро определит по коду',
        <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="computeTotal" spellCheck={false} autoFocus />,
        'Вынести', isJavaIdentifier(code), () => ({ op: 'extractMethod', nodeIds: ids, name: code.trim() }))}

      {mode === 'move' && form('Куда переместить',
        <select value={target} onChange={(e) => setTarget(e.target.value)} autoFocus>
          {targets.map((t) => <option key={t.id} value={t.id}>{t.label}</option>)}
        </select>,
        'Переместить', !!target, () => ({ op: 'move', ...targetOf(), targetId: target }))}

      {mode === 'delete' && (
        <div className="inspector-form">
          <div>
            {many ? `Удалить ${selection.length} блока(ов)` : `Удалить «${node.label}»`}
            {selection.some((n) => CONTAINERS.includes(n.kind)) && ' вместе с телом'}?
          </div>
          <div className="inspector-actions">
            <button className="danger" disabled={busy} onClick={() => void submit({ op: 'delete', ...targetOf() })}>{busy ? 'Удаляем…' : 'Да, удалить'}</button>
            <button onClick={() => setMode('view')} disabled={busy}>Отмена</button>
          </div>
        </div>
      )}

      {error && <div className="error inspector-error">{error}</div>}
    </div>
  );
}
