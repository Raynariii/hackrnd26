import type { VerifyCase, VerifyReport } from '../api/types';
import { caseMismatch, growthExponent, growthLabel, speedup, verdict, type CaseMismatch } from '../flow/verify';
import { BenchmarkChart } from './BenchmarkChart';

interface Props {
  report: VerifyReport;
  running: boolean;
  onRerun: () => void;
  onClose: () => void;
  /** Скачать проект с JUnit-тестами (GET /export) — те же проверки, что можно запустить без нашего сервиса. */
  onExport?: () => void;
  exporting?: boolean;
}

const VERDICT = {
  preserved: { icon: '✓', text: 'Поведение совпадает', cls: 'ok' },
  changed: { icon: '✗', text: 'Поведение изменилось', cls: 'fail' },
  'not-compiled': { icon: '✗', text: 'Текущая версия не компилируется', cls: 'fail' },
  empty: { icon: '?', text: 'Нет тестов для сравнения', cls: 'muted' },
} as const;

const CHANNEL: Record<CaseMismatch['channel'], string> = {
  stdout: 'вывод (stdout)',
  stderr: 'ошибки (stderr)',
  exitCode: 'код выхода',
  compile: 'компиляция',
  timeout: 'таймаут',
};

const preview = (s: string | undefined, max = 400) => (!s ? '(пусто)' : s.length > max ? `${s.slice(0, max)}…` : s);

function Output({ title, text, mark }: { title: string; text: string | undefined; mark: number | null }) {
  const lines = (text ?? '').replace(/\r\n/g, '\n').replace(/\n$/, '').split('\n');
  // Показываем окно вокруг расхождения, а не весь вывод.
  const from = mark ? Math.max(0, mark - 4) : 0;
  const shown = lines.slice(from, from + 12);
  return (
    <div className="vs-output">
      <div className="vs-output-title">{title}</div>
      <pre>
        {from > 0 && <span className="muted">…{'\n'}</span>}
        {shown.map((l, i) => (
          <span key={i} className={from + i + 1 === mark ? 'vs-diff-line' : undefined}>
            <span className="vs-ln">{from + i + 1}</span>
            {l}
            {'\n'}
          </span>
        ))}
        {from + shown.length < lines.length && <span className="muted">…</span>}
      </pre>
    </div>
  );
}

function CaseRow({ c }: { c: VerifyCase }) {
  const m = caseMismatch(c);
  const mark = m?.diff?.line ?? null;
  const stream = m?.channel === 'stderr' ? 'stderr' : 'stdout';
  return (
    <details className={`vs-case ${c.ok ? 'ok' : 'fail'}`} open={!c.ok}>
      <summary>
        <span className="vs-case-icon">{c.ok ? '✓' : '✗'}</span>
        <span className="vs-case-name">{c.name}</span>
        {m && <span className="badge">{CHANNEL[m.channel]}{mark ? `, строка ${mark}` : ''}</span>}
      </summary>
      {c.mismatch && <div className="vs-mismatch">{c.mismatch}</div>}
      {c.input !== undefined && (
        <div className="vs-output">
          <div className="vs-output-title">Вход (stdin)</div>
          <pre>{preview(c.input)}</pre>
        </div>
      )}
      {/* Без actual (текущая версия не компилируется) сравнивать нечего — причина уже в mismatch. */}
      {c.expected && c.actual && (
        <div className="vs-compare">
          <Output title={`Ожидалось — версия 1 (${stream})`} text={c.expected?.[stream]} mark={mark} />
          <Output title={`Получено — текущая (${stream})`} text={c.actual?.[stream]} mark={mark} />
        </div>
      )}
      {m?.channel === 'exitCode' && (
        <div className="vs-mismatch">код выхода: {c.expected?.exitCode ?? '—'} → {c.actual?.exitCode ?? '—'}</div>
      )}
      {m?.channel === 'compile' && c.actual?.compile.output && <pre className="vs-mismatch">{c.actual.compile.output}</pre>}
    </details>
  );
}

export function VerifyScreen({ report, running, onRerun, onClose, onExport, exporting }: Props) {
  const v = VERDICT[verdict(report)];
  const bench = report.benchmark ?? [];
  const sp = speedup(bench);
  const before = growthLabel(growthExponent(bench, 'baselineMs'));
  const after = growthLabel(growthExponent(bench, 'currentMs'));
  // Сначала упавшие — ради них экран и открывают.
  const cases = [...report.cases].sort((a, b) => Number(a.ok) - Number(b.ok));
  const preserved = verdict(report) === 'preserved';

  const speedSection = bench.length >= 2 && (
    <div className="vs-section">
      <h3>Время выполнения</h3>
      <div className="vs-speed">
        {sp && (
          // Ускорение засчитывается только при совпавшем поведении — иначе это просто другая программа.
          <span className={!preserved ? 'muted' : sp.ratio >= 1 ? 'ok' : 'fail'}>
            {sp.ratio >= 1 ? `в ${sp.ratio.toFixed(1)}× быстрее` : `в ${(1 / sp.ratio).toFixed(1)}× медленнее`} при n ={' '}
            {sp.n.toLocaleString('ru-RU')}
          </span>
        )}
        {before && after && <span className="muted">рост: до {before} → после {after}</span>}
      </div>
      {!preserved && <div className="vs-mismatch">Поведение изменилось — ускорение не засчитывается, пока тесты не зелёные.</div>}
      <BenchmarkChart points={bench} />
    </div>
  );

  const casesSection = (
    <div className="vs-section">
      <h3>Тест-кейсы ({report.total})</h3>
      {cases.length === 0 && <div className="muted">Сервер не прислал ни одного кейса.</div>}
      {cases.map((c, i) => <CaseRow key={`${c.name}-${i}`} c={c} />)}
    </div>
  );

  return (
    <section className="verify-screen" aria-label="Проверка поведения">
      <header className={`vs-head ${v.cls}`}>
        <span className="vs-icon" aria-hidden>{v.icon}</span>
        <div className="vs-title">
          <strong>{v.text}</strong>
          <span>
            {report.passed} из {report.total} тестов
            {report.baselineVersion !== undefined && report.currentVersion !== undefined &&
              ` · эталон v${report.baselineVersion} → текущая v${report.currentVersion}`}
          </span>
        </div>
        <div className="vs-actions">
          <button onClick={onRerun} disabled={running}>{running ? 'Проверяем…' : 'Перепроверить'}</button>
          {onExport && (
            <button onClick={onExport} disabled={exporting} title="Zip с текущим кодом и JUnit-тестами против версии 1; запуск — mvn test">
              {exporting ? 'Готовим архив…' : '⬇ Скачать с JUnit-тестами'}
            </button>
          )}
          <button onClick={onClose} aria-label="Закрыть">✕</button>
        </div>
      </header>

      <div className="vs-body">
        {/* При расхождении главное — какие кейсы упали; при совпадении — сколько выиграли по времени. */}
        {preserved ? <>{speedSection}{casesSection}</> : <>{casesSection}{speedSection}</>}
      </div>
    </section>
  );
}
