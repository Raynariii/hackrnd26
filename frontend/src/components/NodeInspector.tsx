import { useEffect, useState } from 'react';
import type { Graph, GraphNode, GraphOpRequest, NodeKind } from '../api/types';
import { allowedOps, editHint, INSERT_KINDS } from '../flow/edit';

type Mode = 'view' | 'edit' | 'insert' | 'delete';

interface Props {
  node: GraphNode;
  level: Graph['level'];
  /** Выполнить операцию; вернуть текст ошибки для пользователя или null при успехе. */
  onOp: (req: Omit<GraphOpRequest, 'version'>) => Promise<string | null>;
}

/** Выбранный узел и правка на графе: изменить / вставить после / удалить (POST /graph/ops). */
export function NodeInspector({ node, level, onOp }: Props) {
  const ops = allowedOps(node, level);
  const [mode, setMode] = useState<Mode>('view');
  const [code, setCode] = useState('');
  const [kind, setKind] = useState<NodeKind>('operation');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Другой узел — сбрасываем незавершённую правку.
  useEffect(() => {
    setMode('view');
    setError(null);
  }, [node.id]);

  const open = (m: Mode) => {
    setError(null);
    setMode(m);
    if (m === 'edit') setCode(node.code ?? '');
    if (m === 'insert') setCode('');
  };

  const submit = async (req: Omit<GraphOpRequest, 'version'>) => {
    setBusy(true);
    setError(null);
    const err = await onOp(req);
    setBusy(false);
    if (err) setError(err);
    else setMode('view');
  };

  const insertKind = INSERT_KINDS.find((k) => k.kind === kind)!;
  const canSubmitInsert = insertKind.optionalCode || code.trim().length > 0;

  return (
    <div className="inspector">
      <h3>Узел</h3>
      <div>{node.label} <span className="muted">({node.kind})</span></div>
      {node.code && <code className="inspector-code">{node.code}</code>}
      {node.range && <div className="muted">строки {node.range.startLine}{node.range.endLine !== node.range.startLine ? `–${node.range.endLine}` : ''}</div>}

      {mode === 'view' && ops.size > 0 && (
        <div className="inspector-actions">
          {ops.has('editNode') && <button onClick={() => open('edit')}>Изменить</button>}
          {ops.has('insertAfter') && <button onClick={() => open('insert')}>Вставить после</button>}
          {ops.has('delete') && <button className="danger" onClick={() => open('delete')}>Удалить</button>}
        </div>
      )}
      {mode === 'view' && ops.size === 0 && (
        <div className="muted inspector-note">
          {node.kind === 'end' ? 'Конец метода не редактируется' : level === 'overview' ? 'Откройте метод двойным кликом, чтобы править блоки' : 'Этот узел не редактируется'}
        </div>
      )}

      {mode === 'edit' && (
        <form className="inspector-form" onSubmit={(e) => { e.preventDefault(); void submit({ op: 'editNode', nodeId: node.id, code }); }}>
          <label className="muted">{editHint(node)}</label>
          <textarea value={code} onChange={(e) => setCode(e.target.value)} rows={3} spellCheck={false} autoFocus
            onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) e.currentTarget.form?.requestSubmit(); }} />
          <div className="inspector-actions">
            <button type="submit" className="primary" disabled={busy || !code.trim() || code === node.code}>{busy ? 'Применяем…' : 'Применить'}</button>
            <button type="button" onClick={() => setMode('view')} disabled={busy}>Отмена</button>
          </div>
        </form>
      )}

      {mode === 'insert' && (
        <form className="inspector-form" onSubmit={(e) => { e.preventDefault(); void submit({ op: 'insertAfter', nodeId: node.id, kind, code: code.trim() }); }}>
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
            {node.kind === 'start' || node.kind === 'case' || node.kind === 'catch' || node.kind === 'finally'
              ? 'Вставится в начало тела'
              : ['loop', 'condition', 'try', 'switch'].includes(node.kind) ? 'Вставится после всей конструкции' : 'Вставится следующим оператором'}
            {insertKind.optionalCode && ' · код можно не вводить'}
          </label>
          <div className="inspector-actions">
            <button type="submit" className="primary" disabled={busy || !canSubmitInsert}>{busy ? 'Вставляем…' : 'Вставить'}</button>
            <button type="button" onClick={() => setMode('view')} disabled={busy}>Отмена</button>
          </div>
        </form>
      )}

      {mode === 'delete' && (
        <div className="inspector-form">
          <div>
            Удалить «{node.label}»
            {['loop', 'condition', 'try', 'switch', 'catch', 'case'].includes(node.kind) && ' вместе с телом'}?
          </div>
          <div className="inspector-actions">
            <button className="danger" disabled={busy} onClick={() => void submit({ op: 'delete', nodeId: node.id })}>{busy ? 'Удаляем…' : 'Да, удалить'}</button>
            <button onClick={() => setMode('view')} disabled={busy}>Отмена</button>
          </div>
        </div>
      )}

      {error && <div className="error inspector-error">{error}</div>}
    </div>
  );
}
