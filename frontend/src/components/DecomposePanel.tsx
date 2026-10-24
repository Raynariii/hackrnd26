import type { DecomposeResult, Graph, GraphNode } from '../api/types';
import { classNodes, memberNode, sharedMembers, type ProposedClass } from '../flow/decompose';

interface Props {
  result: DecomposeResult | null;
  /** Предложение снято с другой версии кода — показываем, но предупреждаем. */
  stale: boolean;
  busy: boolean;
  /** Обзор проекта: по нему члены предложения превращаются в узлы. */
  overview: Graph | null;
  activeClass: string | null;
  onPropose: () => void;
  onShowClass: (cls: ProposedClass, nodes: GraphNode[]) => void;
  onShowMember: (node: GraphNode) => void;
}

/** «Предложить разбиение»: новые классы, их ответственность и члены (POST /decompose, apply=false). */
export function DecomposePanel({ result, stale, busy, overview, activeClass, onPropose, onShowClass, onShowMember }: Props) {
  const shared = result ? sharedMembers(result.proposal) : new Set<string>();
  return (
    <div className="decompose">
      <div className="decompose-head">
        <h3>Разбиение на классы</h3>
        <button disabled={busy} onClick={onPropose}>{busy ? 'Считаем…' : result ? 'Пересчитать' : 'Предложить разбиение'}</button>
      </div>
      {!result && <div className="muted">Сгруппирует методы и поля монолита по ответственности</div>}
      {result && stale && <div className="muted decompose-stale">Код изменился после расчёта — пересчитайте</div>}
      {result && result.proposal.length === 0 && <div className="muted">Разбивать нечего: монолитных классов не найдено</div>}
      {result?.proposal.map((cls) => {
        const nodes = overview ? classNodes(overview, cls) : [];
        return (
          <div key={cls.className} className={`decompose-class ${activeClass === cls.className ? 'active' : ''}`}>
            <button className="decompose-class-head" onClick={() => onShowClass(cls, nodes)} disabled={nodes.length === 0}
              title="Выделить члены на обзоре">
              <strong>{cls.className}</strong>
              <span className="muted">{cls.members.length}</span>
            </button>
            {cls.responsibility && <div className="muted">{cls.responsibility}</div>}
            <ul>
              {cls.members.map((m) => {
                const node = overview ? memberNode(overview, m) : null;
                return (
                  <li key={m}>
                    {node ? <button className="link-button" onClick={() => onShowMember(node)}><code>{m}</code></button> : <code>{m}</code>}
                    {shared.has(m) && <span className="badge" title="Член нужен нескольким новым классам — решите вручную, где он живёт">общий</span>}
                  </li>
                );
              })}
            </ul>
          </div>
        );
      })}
      {result?.notes?.map((n, i) => <div key={i} className="muted decompose-note">{n}</div>)}
      {result && result.proposal.length > 0 && (
        <div className="muted decompose-note">Применение разбиения пока не поддержано сервером — это предложение для ручного рефакторинга</div>
      )}
    </div>
  );
}
