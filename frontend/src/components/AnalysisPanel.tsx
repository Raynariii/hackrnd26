import { useState } from 'react';
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

function FindingCard({ f, onSelect, onFix }: { f: Finding; onSelect: Props['onSelect']; onFix: Props['onFix'] }) {
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
      className={`finding sev-${f.severity}`}
      role="button"
      tabIndex={0}
      onClick={() => onSelect(f)}
      onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget && onSelect(f)}
    >
      <div className="finding-head">
        <strong>{f.title}</strong>
        <span className="badge">{SEVERITY[f.severity]}</span>
        <span className="muted">{f.file}:{f.startLine}</span>
      </div>
      <div>{f.explanation}</div>
      {f.complexity && (
        <div className="complexity">
          {f.complexity.current}
          {f.complexity.after && <> → <b>{f.complexity.after}</b></>}
          {f.expectedEffect && <span className="muted"> · {f.expectedEffect}</span>}
        </div>
      )}
      {f.fix && (
        <div className="finding-fix" onClick={(e) => e.stopPropagation()}>
          {f.fix.autoApplicable ? (
            <button className="primary" disabled={busy} onClick={fix} title={f.fix.description}>
              {busy ? 'Исправляем…' : 'Исправить автоматически'}
            </button>
          ) : (
            <span className="muted">Нужна ручная правка</span>
          )}
          {f.fix.description && <span className="muted finding-fix-desc">{f.fix.description}</span>}
        </div>
      )}
      {error && <div className="error finding-error" onClick={(e) => e.stopPropagation()}>{error}</div>}
    </div>
  );
}

export function AnalysisPanel({ diagnostics, onSelect, onFix, applied, onVerify, onUndo, onDismissApplied, verifying }: Props) {
  return (
    <div className="analysis">
      {applied && (
        <div className="applied-fix" role="status">
          <div className="applied-head">
            <strong className="ok">✓ Исправление применено</strong>
            <button className="link-button" onClick={onDismissApplied} aria-label="Скрыть">✕</button>
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
      {!diagnostics && <div className="muted">Нет данных анализа</div>}
      {diagnostics && (
        <>
          <h3>Проблемы ({diagnostics.findings.length})</h3>
          {diagnostics.findings.map((f) => <FindingCard key={f.id} f={f} onSelect={onSelect} onFix={onFix} />)}
          <h3>Метрики</h3>
          <table>
            <thead><tr><th>Метод</th><th>Строк</th><th>Цикл.</th><th>O()</th></tr></thead>
            <tbody>
              {diagnostics.metrics.map((m) => (
                <tr key={m.methodId}><td>{m.name}</td><td>{m.lines}</td><td>{m.cyclomatic ?? '—'}</td><td>{m.complexity ?? '—'}</td></tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
