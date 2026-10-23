import { useState } from 'react';
import type { RunResult, VerifyReport } from '../api/types';

interface Props {
  run: RunResult | null;
  verify: VerifyReport | null;
}

export function BottomPanel({ run, verify }: Props) {
  const [tab, setTab] = useState<'console' | 'tests'>('console');
  return (
    <div className="bottom">
      <div className="tabs">
        <button className={tab === 'console' ? 'active' : ''} onClick={() => setTab('console')}>Консоль</button>
        <button className={tab === 'tests' ? 'active' : ''} onClick={() => setTab('tests')}>Результат тестов</button>
      </div>
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
