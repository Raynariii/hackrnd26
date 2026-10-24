import { useState } from 'react';
import type { BenchPoint } from '../flow/verify';

// Две серии: «до» (эталон, версия 1) и «после» (текущая). Цвета — категориальные слоты 2 и 1 тёмной темы,
// проверены validate_palette.js на фоне --panel (#111a2e). «До» ещё и пунктиром — не только цветом.
const SERIES = [
  { key: 'baselineMs' as const, name: 'до', color: 'var(--series-before)', dash: '6 4' },
  { key: 'currentMs' as const, name: 'после', color: 'var(--series-after)', dash: undefined },
];

const W = 560;
const H = 220;
const PAD = { top: 16, right: 64, bottom: 34, left: 52 };

const fmtMs = (ms: number) => (ms >= 1000 ? `${(ms / 1000).toFixed(ms >= 10000 ? 0 : 1)} с` : `${Math.round(ms)} мс`);
const fmtN = (n: number) => n.toLocaleString('ru-RU');

/** «Красивые» деления оси: 1/2/5 × 10^k. */
function ticks(max: number, count = 4): number[] {
  if (max <= 0) return [0];
  const raw = max / count;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw)!;
  const out: number[] = [];
  for (let v = 0; v <= max + step * 0.001; v += step) out.push(v);
  if (out.at(-1)! < max) out.push(out.at(-1)! + step);
  return out;
}

export function BenchmarkChart({ points }: { points: BenchPoint[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const data = [...points].sort((a, b) => a.n - b.n);
  if (data.length < 2) return null;

  const yTicks = ticks(Math.max(...data.flatMap((p) => [p.baselineMs, p.currentMs])));
  const yMax = yTicks.at(-1)!;
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  // n обычно растёт удвоением (n, 2n, 4n) — шкала по log n даёт равные шаги.
  const lx = data.map((p) => Math.log(p.n));
  const x = (i: number) => PAD.left + ((lx[i] - lx[0]) / (lx.at(-1)! - lx[0] || 1)) * innerW;
  const y = (v: number) => PAD.top + innerH - (v / yMax) * innerH;
  const hovered = hover === null ? null : data[hover];

  return (
    <figure className="bench">
      <div className="bench-legend" aria-hidden>
        {SERIES.map((s) => (
          <span key={s.key}>
            <svg width="22" height="10"><line x1="1" y1="5" x2="21" y2="5" stroke={s.color} strokeWidth="2" strokeDasharray={s.dash} /></svg>
            {s.name === 'до' ? 'до (версия 1)' : 'после (текущая)'}
          </span>
        ))}
      </div>
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="bench-svg"
        role="img"
        aria-label="Время выполнения до и после по размеру входа"
        onMouseLeave={() => setHover(null)}
      >
        {yTicks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className="bench-grid" />
            <text x={PAD.left - 8} y={y(t)} className="bench-tick" textAnchor="end" dominantBaseline="middle">{fmtMs(t)}</text>
          </g>
        ))}
        {data.map((p, i) => (
          <text key={p.n} x={x(i)} y={H - PAD.bottom + 18} className="bench-tick" textAnchor="middle">n = {fmtN(p.n)}</text>
        ))}
        {hovered && <line x1={x(hover!)} x2={x(hover!)} y1={PAD.top} y2={PAD.top + innerH} className="bench-crosshair" />}
        {SERIES.map((s) => (
          <g key={s.key}>
            <polyline
              points={data.map((p, i) => `${x(i)},${y(p[s.key])}`).join(' ')}
              fill="none"
              stroke={s.color}
              strokeWidth={2}
              strokeDasharray={s.dash}
              strokeLinejoin="round"
            />
            {data.map((p, i) => (
              <circle key={p.n} cx={x(i)} cy={y(p[s.key])} r={hover === i ? 5 : 4} fill={s.color} className="bench-dot" />
            ))}
            {/* прямая подпись у конца линии */}
            <text x={x(data.length - 1) + 10} y={y(data.at(-1)![s.key])} className="bench-label" dominantBaseline="middle">{s.name}</text>
          </g>
        ))}
        {/* зоны наведения шире точек: вся полоса по x вокруг каждого n */}
        {data.map((p, i) => {
          const left = i === 0 ? PAD.left - 20 : (x(i - 1) + x(i)) / 2;
          const right = i === data.length - 1 ? W - PAD.right + 20 : (x(i) + x(i + 1)) / 2;
          return <rect key={p.n} x={left} y={PAD.top} width={right - left} height={innerH} fill="transparent" onMouseEnter={() => setHover(i)} />;
        })}
      </svg>
      {hovered && (
        <div className="bench-tooltip" style={{ left: `${(x(hover!) / W) * 100}%` }}>
          <strong>n = {fmtN(hovered.n)}</strong>
          <div>до: {fmtMs(hovered.baselineMs)}</div>
          <div>после: {fmtMs(hovered.currentMs)}</div>
          {hovered.currentMs > 0 && <div className="muted">× {(hovered.baselineMs / hovered.currentMs).toFixed(1)}</div>}
        </div>
      )}
      <table className="bench-table">
        <thead><tr><th>n</th><th>до</th><th>после</th><th>ускорение</th></tr></thead>
        <tbody>
          {data.map((p) => (
            <tr key={p.n}>
              <td>{fmtN(p.n)}</td>
              <td>{fmtMs(p.baselineMs)}</td>
              <td>{fmtMs(p.currentMs)}</td>
              <td>{p.currentMs > 0 ? `× ${(p.baselineMs / p.currentMs).toFixed(1)}` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}
