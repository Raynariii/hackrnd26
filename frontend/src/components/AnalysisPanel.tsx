import { useState } from 'react';
import { CircleAlert, CircleCheck, Info, Sparkles, TriangleAlert, Wand2, X } from 'lucide-react';
import type { Diagnostics, Finding, VerifyReport } from '../api/types';
import { diffLines } from '../flow/diff';

/** Последнее применённое авто-исправление — показываем дифф и даём проверить или отменить. */
export interface AppliedFix {
  /** Что применено: авто-исправление находки (по умолчанию) или разбиение на классы — баннер в своей вкладке. */
  kind?: 'fix' | 'decompose';
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
  /** Совет LLM по находке; нет — советчика на сервере нет, кнопка скрыта. */
  onAdvice?: (finding: Finding) => Promise<AdviceReply>;
}

/** Ответ советчика для карточки: текст, «выключен» (нет ключа на сервере) или ошибка. */
export type AdviceReply = { text: string; model: string | null } | { off: true } | { error: string };

/** Метка важности; что она значит для пользователя — во всплывающей подсказке. */
const SEVERITY: Record<Finding['severity'], { label: string; hint: string }> = {
  high: { label: 'Критично', hint: 'Заметно тормозит или может сломать программу — исправьте в первую очередь' },
  medium: { label: 'Важно', hint: 'Код работает, но его сложнее читать и менять; исправление безопасно' },
  low: { label: 'Мелочь', hint: 'Улучшение стиля; на поведение и скорость не влияет' },
};

function DiffView({ diff }: { diff: string }) {
  return (
    <pre className="diff">
      {diffLines(diff).map((l, i) => (
        // каждая строка — блок: без лишнего \n, иначе между строками появляются пустые
        <span key={i} className={`diff-line diff-${l.kind}`}>{l.text || ' '}</span>
      ))}
    </pre>
  );
}

/** Совет в markdown: блоки ``` — код, остальное — абзацы как есть (без HTML: текст от модели не вставляем разметкой). */
function AdviceText({ text }: { text: string }) {
  const parts = text.split(/```[a-zA-Z]*\n?/);
  return (
    <>
      {parts.map((part, i) => (i % 2 ? <pre key={i} className="advice-code">{part.replace(/\n$/, '')}</pre> : part.trim() && <p key={i}>{part.trim()}</p>))}
    </>
  );
}

function FindingCard({ f, onSelect, onFix, onAdvice, open, onToggle }: { f: Finding; onSelect: Props['onSelect']; onFix: Props['onFix']; onAdvice?: Props['onAdvice']; open: boolean; onToggle: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [advice, setAdvice] = useState<AdviceReply | 'loading' | null>(null);
  const ask = async () => {
    setAdvice('loading');
    setAdvice(await onAdvice!(f));
  };
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
          <span className="badge" title={SEVERITY[f.severity].hint}>
            {f.severity !== 'low' && <TriangleAlert size={11} />}{SEVERITY[f.severity].label}
          </span>
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
              <Wand2 size={14} />{busy ? 'Исправляем…' : 'Исправить автоматически'}
            </button>
          ) : (
            <span className="muted">Нужна ручная правка</span>
          )}
          {open && f.fix.description && <span className="muted finding-fix-desc">{f.fix.description}</span>}
        </div>
      )}
      {error && <div className="error finding-error" onClick={(e) => e.stopPropagation()}>{error}</div>}
      {open && onAdvice && (
        <div className="advice" onClick={(e) => e.stopPropagation()}>
          {(advice === null || (typeof advice === 'object' && 'error' in advice)) && (
            <button className="ghost advice-ask" onClick={ask} title="Объяснение и вариант исправления от LLM; применяется правкой кода и проверяется тестами">
              <Sparkles size={14} />Спросить совет у ИИ
            </button>
          )}
          {advice === 'loading' && <span className="muted"><Sparkles size={14} />Советчик думает…</span>}
          {advice && typeof advice === 'object' && 'off' in advice && <span className="muted">Советчик выключен на сервере (нет ключа API)</span>}
          {advice && typeof advice === 'object' && 'error' in advice && <span className="error">{advice.error}</span>}
          {advice && typeof advice === 'object' && 'text' in advice && (
            <div className="advice-body">
              <div className="advice-title"><Sparkles size={14} />Совет ИИ{advice.model && <span className="muted"> · {advice.model}</span>}</div>
              <AdviceText text={advice.text} />
              <div className="muted advice-note">Совет — не правка: перенесите его в код и нажмите «Проверить поведение»</div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** Баннер «применено»: что сделал сервер, отчёт quality gate, дифф, проверка и отмена. */
export function AppliedBanner({ applied, onVerify, onUndo, onDismiss, verifying }: { applied: AppliedFix; onVerify: () => void; onUndo: () => void; onDismiss: () => void; verifying: boolean }) {
  return (
    <div className="applied-fix" role="status">
      <div className="applied-head">
        <strong className="ok"><CircleCheck size={16} />{applied.kind === 'decompose' ? 'Разбиение применено' : 'Исправление применено'}</strong>
        <button className="link-button" onClick={onDismiss} aria-label="Скрыть"><X size={16} /></button>
      </div>
      <div>{applied.title}</div>
      {applied.description && <div className="muted">{applied.description}</div>}
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
  );
}

export function AnalysisPanel({ diagnostics, onSelect, onFix, applied, onVerify, onUndo, onDismissApplied, verifying, onAdvice }: Props) {
  /** Раскрытая карточка: свернутые показывают суть, подробности — по клику (клик же ведёт к коду и блоку). */
  const [openId, setOpenId] = useState<string | null>(null);
  return (
    <div className="analysis">
      {applied && applied.kind !== 'decompose' && (
        <AppliedBanner applied={applied} onVerify={onVerify} onUndo={onUndo} onDismiss={onDismissApplied} verifying={verifying} />
      )}
      {!diagnostics && <div className="empty-state">Откройте проект — здесь появятся найденные проблемы</div>}
      {diagnostics && diagnostics.findings.length === 0 && <div className="empty-state">Проблем не найдено</div>}
      {diagnostics?.findings.map((f) => (
        <FindingCard key={f.id} f={f} onSelect={onSelect} onFix={onFix} onAdvice={onAdvice} open={openId === f.id}
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
