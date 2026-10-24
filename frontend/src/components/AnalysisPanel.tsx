import { useState } from 'react';
import { CircleAlert, CircleCheck, Info, TriangleAlert, X } from 'lucide-react';
import type { Diagnostics, Finding, VerifyReport } from '../api/types';

/** Последнее применённое авто-исправление — показываем дифф и даём проверить или отменить. */
export interface AppliedFix {
  title: string;
  description: string;
  diff: string;
  /** Отчёт quality gate сервера: предыдущая версия vs исправленная (без него исправление не коммитится). */
  gate?: VerifyReport | null;
}

interface Props {
  diagnostics: Diagnostics | null;
  onSelect: (finding: Finding) => void;
  /** Применить авто-исправление; вернуть текст ошибки для пользователя или null. */
  onFix: (finding: Finding) => Promise<string | null>;
  applied: AppliedFix | null;
  onVerify: () => void;
  onUndo: () => void;
  onDismissApplied: () => void;
  verifying: boolean;
}

const SEVERITY: Record<Finding['severity'], string> = { high: 'Высокая', medium: 'Средняя', low: 'Низкая' };

function DiffView({ diff }: { diff: string }) {
  const lines = diff.split('\n').filter((l) => !l.startsWith('---') && !l.startsWith('+++'));
  return (
    <pre className="diff">
      {lines.map((l, i) => (
        <span key={i} className={l.startsWith('+') ? 'diff-add' : l.startsWith('-') ? 'diff-del' : l.startsWith('@@') ? 'diff-hunk' : undefined}>
          {l}
          {'\n'}
        </span>
      ))}
    </pre>
  );
}

function FindingCard({ f, onSelect, onFix, open, onToggle }: { f: Finding; onSelect: Props['onSelect']; onFix: Props['onFix']; open: boolean; onToggle: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fix = async () => {
    setBusy(true);
    setError(null);
    setError(await onFix(f));
    setBusy(false);
  };
  return (
    <div
      className={`finding sev-${f.severity} ${open ? 'open' : ''}`}
      role="button"
      tabIndex={0}
      aria-expanded={open}
      onClick={() => { onSelect(f); onToggle(); }}
      onKeyDown={(e) => { if ((e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget) { onSelect(f); onToggle(); } }}
    >
      <div className="finding-head">
        {f.severity === 'high' ? <CircleAlert size={16} /> : f.severity === 'medium' ? <TriangleAlert size={16} /> : <Info size={16} />}
        <strong>{f.title}</strong>
        <span className="finding-meta">
          <span className="badge">{SEVERITY[f.severity]}</span>
          <span className="muted">{f.file.split('/').at(-1)}:{f.startLine}</span>
        </span>
      </div>
      <div className="finding-text">{f.explanation}</div>
      {f.complexity && (
        <div className="complexity">
          <span className="complexity-change">
            {f.complexity.current}
            {f.complexity.after && <> → <b>{f.complexity.after}</b></>}
          </span>
          {open && f.expectedEffect && <span className="complexity-effect">{f.expectedEffect}</span>}
        </div>
      )}
      {f.fix && (
        <div className="finding-fix" onClick={(e) => e.stopPropagation()}>
          {f.fix.autoApplicable ? (
            <button disabled={busy} onClick={fix} title={f.fix.description}>
              {busy ? 'Исправляем…' : 'Исправить автоматически'}
            </button>
          ) : (
            <span className="muted">Нужна ручная правка</span>
          )}
          {open && f.fix.description && <span className="muted finding-fix-desc">{f.fix.description}</span>}
        </div>
      )}
      {error && <div className="error finding-error" onClick={(e) => e.stopPropagation()}>{error}</div>}
    </div>
  );
}

export function AnalysisPanel({ diagnostics, onSelect, onFix, applied, onVerify, onUndo, onDismissApplied, verifying }: Props) {
  /** Раскрытая карточка: свернутые показывают суть, подробности — по клику (клик же ведёт к коду и блоку). */
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <div className="analysis">
      {applied && (
        <div className="applied-fix" role="status">
          <div className="applied-head">
            <strong className="ok"><CircleCheck size={16} />Исправление применено</strong>
            <button className="link-button" onClick={onDismissApplied} aria-label="Скрыть"><X size={16} /></button>
          </div>
          <div>{applied.title}</div>
          <div className="muted">{applied.description}</div>
          {applied.gate && (
            <div className={applied.gate.behaviorPreserved ? 'ok' : 'fail'}>
              Quality gate: {applied.gate.passed} из {applied.gate.total} тестов — поведение {applied.gate.behaviorPreserved ? 'не изменилось' : 'изменилось'}
            </div>
          )}
          <details>
            <summary>Изменения в коде</summary>
            <DiffView diff={applied.diff} />
          </details>
          <div className="inspector-actions">
            <button className="primary" disabled={verifying} onClick={onVerify}>{verifying ? 'Проверяем…' : 'Проверить поведение'}</button>
            <button onClick={onUndo}>Отменить</button>
          </div>
        </div>
      )}
      {!diagnostics && <div className="empty-state">Откройте проект — здесь появятся найденные проблемы</div>}
      {diagnostics && diagnostics.findings.length === 0 && <div className="empty-state">Проблем не найдено</div>}
      {diagnostics?.findings.map((f) => (
        <FindingCard key={f.id} f={f} onSelect={onSelect} onFix={onFix} open={openId === f.id}
          onToggle={() => setOpenId((id) => (id === f.id ? null : f.id))} />
      ))}
    </div>
  );
}

/** Метрики методов: строки, цикломатическая сложность, Big O — отдельная вкладка правой панели. */
export function MetricsPanel({ diagnostics }: { diagnostics: Diagnostics | null }) {
  if (!diagnostics) return <div className="empty-state">Нет данных анализа</div>;
  return (
    <table>
      <thead><tr><th>Метод</th><th>Строк</th><th>Цикл.</th><th>O()</th></tr></thead>
      <tbody>
        {diagnostics.metrics.map((m) => (
          <tr key={m.methodId}><td>{m.name}</td><td>{m.lines}</td><td>{m.cyclomatic ?? '—'}</td><td><code>{m.complexity ?? '—'}</code></td></tr>
        ))}
      </tbody>
    </table>
  );
}
