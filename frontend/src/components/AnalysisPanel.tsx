import type { Diagnostics, Finding } from '../api/types';

interface Props {
  diagnostics: Diagnostics | null;
  onSelect: (finding: Finding) => void;
}

const SEVERITY: Record<Finding['severity'], string> = { high: 'Высокая', medium: 'Средняя', low: 'Низкая' };

export function AnalysisPanel({ diagnostics, onSelect }: Props) {
  if (!diagnostics) return <div className="muted">Нет данных анализа</div>;
  return (
    <div className="analysis">
      <h3>Проблемы ({diagnostics.findings.length})</h3>
      {diagnostics.findings.map((f) => (
        <button key={f.id} className={`finding sev-${f.severity}`} onClick={() => onSelect(f)}>
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
            </div>
          )}
        </button>
      ))}
      <h3>Метрики</h3>
      <table>
        <thead><tr><th>Метод</th><th>Строк</th><th>Цикл.</th><th>O()</th></tr></thead>
        <tbody>
          {diagnostics.metrics.map((m) => (
            <tr key={m.methodId}><td>{m.name}</td><td>{m.lines}</td><td>{m.cyclomatic ?? '—'}</td><td>{m.complexity ?? '—'}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
