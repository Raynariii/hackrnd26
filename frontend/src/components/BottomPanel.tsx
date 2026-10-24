import { useState } from 'react';
import type { RunResult, VerifyReport } from '../api/types';

interface Props {
  run: RunResult | null;
  verify: VerifyReport | null;
  onOpenReport: () => void;
  stdin: string;
  onStdin: (value: string) => void;
  onRun: () => void;
  running: boolean;
  canRun: boolean;
}

export function BottomPanel({ run, verify, onOpenReport, stdin, onStdin, onRun, running, canRun }: Props) {
  const [tab, setTab] = useState<'console' | 'tests'>('console');
  return (
    <div className="bottom">
      <div className="tabs">
        <button className={tab === 'console' ? 'active' : ''} onClick={() => setTab('console')}>Консоль</button>
        <button className={tab === 'tests' ? 'active' : ''} onClick={() => setTab('tests')}>Результат тестов</button>
      </div>
      {tab === 'console' && (
        <div className="stdin-row">
          <textarea className="stdin" value={stdin} onChange={(e) => onStdin(e.target.value)} rows={2} spellCheck={false}
            placeholder="stdin — ввод программы (Scanner), например: 3 1 2 2"
            onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) onRun(); }} />
          <button disabled={!canRun || running} onClick={onRun} title="Ctrl+Enter">{running ? '…' : '▶'}</button>
        </div>
      )}
      {tab === 'console' && (
        <pre className="console">
          {!run && 'Нажмите «Запуск»'}
          {run && !run.compile.ok && `Ошибка компиляции\n${run.compile.output ?? ''}`}
          {run?.compile.ok && `${run.stdout ?? ''}${run.stderr ?? ''}\n[exit ${run.exitCode ?? '—'}, ${run.timeMs ?? 0} мс${run.timedOut ? ', таймаут' : ''}]`}
        </pre>
      )}
      {tab === 'tests' && (
        <div className="tests">
          {!verify && <span className="muted">Нажмите «Проверить»</span>}
          {verify && (
            <>
              <div className={verify.behaviorPreserved ? 'ok' : 'fail'}>
                {verify.behaviorPreserved ? '✓ Поведение совпадает' : '✗ Поведение изменилось'} — {verify.passed} из {verify.total}
              </div>
              <button className="link-button" onClick={onOpenReport}>Открыть отчёт</button>
              <ul>
                {verify.cases.map((c) => (
                  <li key={c.name} className={c.ok ? 'ok' : 'fail'}>{c.ok ? '✓' : '✗'} {c.name}{c.mismatch ? ` — ${c.mismatch}` : ''}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </div>
  );
}
