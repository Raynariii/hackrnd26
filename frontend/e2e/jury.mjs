// Путь жюри через интерфейс на собранном стеке (docker compose up → http://localhost:3000): браузер → nginx → api → runner.
// Дымовой тест CI (.github/scripts/compose-smoke.py) проверяет тот же стек через API; этот — через UI, как его увидит жюри.
//
// Запуск:  cd frontend && npm i --no-save playwright@1.48.2 && npx playwright install --with-deps chromium
//          JURY_URL=http://localhost:3000 node e2e/jury.mjs [папка для скриншотов]
// Пример — corpus/06-demo-warehouse (README «Сценарий демо»). Любой провал — ненулевой код выхода и скриншот.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = await import(process.env.PLAYWRIGHT_PATH ?? 'playwright');
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const EXAMPLE = path.join(ROOT, 'corpus/06-demo-warehouse');
const URL = process.env.JURY_URL ?? 'http://localhost:3000';
const OUT = process.argv[2] ?? path.join(ROOT, 'frontend/e2e-out');
fs.mkdirSync(OUT, { recursive: true });

let failed = 0;
// В GitHub Actions каждая строка ещё и аннотация: её видно в PR и через API, не открывая лог.
const CI = !!process.env.GITHUB_ACTIONS;
const annotate = (level, text) => { if (CI) console.log(`::${level} title=Путь жюри через UI::${text.replace(/\r?\n/g, ' ')}`); };
const check = (name, ok, extra = '') => {
  if (!ok) failed++;
  const line = `${name}${extra ? ` — ${extra}` : ''}`;
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${line}`);
  if (!ok) annotate('error', line);
};
const errors = [];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, acceptDownloads: true });
page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
// «Failed to load resource» — журнал сети браузера (ожидаемые 4xx, например отказ разбиения), не ошибка приложения
page.on('console', (m) => { if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errors.push(`console: ${m.text()}`); });
const shot = (name) => page.screenshot({ path: path.join(OUT, `${name}.png`) }).catch(() => {});
const version = () => page.locator('.toolbar .muted').first().innerText();
const step = async (name, fn) => {
  try {
    await fn();
  } catch (e) {
    failed++;
    const line = `${name} — ${String(e.message ?? e).split('\n')[0]}`;
    console.log(`FAIL ${line}`);
    annotate('error', line);
    await shot(`fail-${name.replace(/[^\p{L}\d]+/gu, '-')}`);
  }
};
// Отчёт проверки открывается поверх окна после исправления — закрываем, когда он есть.
const closeReport = async () => {
  if (await page.locator('.dialog-backdrop').count()) await page.keyboard.press('Escape');
};

await step('открыть приложение', async () => {
  await page.goto(URL, { timeout: 60_000 });
  await page.locator('.welcome-card').waitFor({ timeout: 30_000 });
});

await step('загрузить пример', async () => {
  const files = fs.readdirSync(EXAMPLE).filter((f) => f.endsWith('.java'))
    .map((f) => ({ name: f, mimeType: 'text/x-java', buffer: fs.readFileSync(path.join(EXAMPLE, f)) }));
  await page.locator('input[type=file]').setInputFiles(files);
  await page.locator('.finding').first().waitFor({ timeout: 60_000 });
  const findings = await page.locator('.finding').count();
  check('обзор и проблемы', findings >= 3 && (await page.locator('.react-flow__node').count()) > 5, `${findings} проблем`);
  check('версия v1', (await version()) === 'v1');
});

await step('запуск со stdin', async () => {
  await page.locator('.stripe [aria-label="Консоль"]').click();
  await page.locator('.stdin').fill(fs.readFileSync(path.join(EXAMPLE, 'inputs/01-basic.txt'), 'utf8'));
  await page.locator('.stdin-row button').click();
  await page.locator('.console', { hasText: '[exit' }).waitFor({ timeout: 60_000 });
  const out = await page.locator('.console').innerText();
  check('консоль: вывод программы и exit 0', /\[exit 0, \d+ мс\]/.test(out) && out.length > 80, out.slice(0, 60).replace(/\n/g, ' '));
});

await step('авто-исправление через quality gate', async () => {
  const card = page.locator('.finding', { hasText: 'contains' }).filter({ has: page.locator('button', { hasText: 'Исправить автоматически' }) }).first();
  await card.locator('button', { hasText: 'Исправить автоматически' }).click();
  await page.locator('.applied-fix').waitFor({ timeout: 120_000 });
  const banner = await page.locator('.applied-fix').innerText();
  check('исправление применено, gate зелёный', /поведение не изменилось/.test(banner), banner.split('\n').slice(0, 4).join(' | '));
  check('версия v2', (await version()) === 'v2');
  // после исправления сразу идёт полная проверка против v1
  await page.locator('.dialog-backdrop .verify-screen').waitFor({ timeout: 180_000 });
  await page.locator('.verify-screen', { hasText: /Поведение (совпадает|изменилось)/ }).waitFor({ timeout: 180_000 });
  const report = await page.locator('.verify-screen').innerText();
  check('проверка против v1: поведение совпадает', /Поведение совпадает/.test(report), report.split('\n').slice(0, 2).join(' | '));
  await shot('01-verify-report');
  await closeReport();
});

await step('блок-схема и Big O выделения', async () => {
  const row = page.locator('[aria-label="Структура кода"] .tree-row', { hasText: 'reconcile()' });
  await row.click();
  await page.waitForTimeout(400);
  await row.dblclick();
  await page.locator('.tabs button', { hasText: 'reconcile()' }).waitFor({ timeout: 30_000 });
  await page.locator('.react-flow__node', { hasText: 'void reconcile()' }).first().click();
  await page.waitForFunction(() => {
    const c = document.querySelector('.inspector-complexity code');
    return c && c.textContent !== '…';
  }, null, { timeout: 30_000 });
  const head = await page.locator('.inspector-head').innerText();
  check('сложность метода от /complexity', /^Метод\s*O\(/.test(head), head.replace(/\n/g, ' '));
});

await step('правка на графе из палитры', async () => {
  const before = await version();
  await page.locator('.palette-item', { hasText: 'Переменная' }).dragTo(page.locator('.react-flow__node', { hasText: 'void reconcile()' }).first());
  await page.locator('.inspector-form input').fill('int juryProbe = 0');
  await page.locator('.inspector-form button[type=submit]').click();
  await page.waitForFunction((v) => document.querySelector('.toolbar .muted')?.textContent !== v && !document.querySelector('.inspector-form'), before, { timeout: 60_000 });
  const code = await page.evaluate(() => [...document.querySelectorAll('.view-lines .view-line')].map((l) => l.textContent.replace(/ /g, ' ')).join('\n'));
  check('вставка: новая версия, строка в коде', code.includes('int juryProbe = 0;'), `версия ${await version()}`);
  await page.locator('.run-widget .primary').click();
  await page.locator('.verify-screen', { hasText: /Поведение (совпадает|изменилось)/ }).waitFor({ timeout: 180_000 });
  check('после правки поведение совпадает', /Поведение совпадает/.test(await page.locator('.verify-screen').innerText()));
  await closeReport();
});

await step('совет ИИ', async () => {
  await page.locator('.analysis-panel .tabs button[data-tab="problems"]').click();
  await page.locator('.finding').first().click();
  const ask = page.locator('.advice-ask');
  if (!(await ask.count())) return check('совет ИИ: кнопки нет — советчика на сервере нет', true);
  await ask.click();
  await page.locator('.advice .muted, .advice-body, .advice .error').first().waitFor({ timeout: 60_000 });
  await page.waitForFunction(() => !document.querySelector('.advice')?.textContent?.includes('думает'), null, { timeout: 90_000 });
  const text = await page.locator('.advice').innerText();
  // без ANTHROPIC_API_KEY — честное «выключен»; с ключом — текст совета
  check('совет ИИ отвечает', /Советчик выключен|Совет ИИ/.test(text), text.slice(0, 60).replace(/\n/g, ' '));
});

await step('разбиение на классы', async () => {
  await page.locator('.analysis-panel .tabs button', { hasText: 'Разбиение' }).click();
  await page.locator('.decompose-head button').click();
  await page.locator('.decompose-class, .decompose .muted').first().waitFor({ timeout: 60_000 });
  const classes = await page.locator('.decompose-class').count();
  check('предложение разбиения', true, `${classes} классов`);
  const apply = page.locator('.decompose-apply button');
  if (classes === 0 || !(await apply.count())) return;
  const before = await version();
  await apply.click();
  await page.waitForFunction(() => !document.querySelector('.decompose-apply button')?.textContent?.includes('Применяем'), null, { timeout: 180_000 });
  // 422 (нечего разбивать или gate не пропустил) — честный отказ, версия та же; иначе новая версия и баннер
  const applied = await page.locator('.decompose .applied-fix').count();
  const refused = await page.locator('.decompose .error').innerText().catch(() => '');
  check('разбиение: применено или понятный отказ', applied ? (await version()) !== before : refused.length > 0 && (await version()) === before,
    applied ? (await page.locator('.decompose .applied-fix').innerText()).split('\n').slice(0, 2).join(' | ') : refused);
  await closeReport();
});

await step('экспорт с тестами', async () => {
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 180_000 }),
    page.locator('.stripe [aria-label="Экспорт с тестами"]').click(),
  ]);
  const file = path.join(OUT, download.suggestedFilename());
  await download.saveAs(file);
  const zip = fs.readFileSync(file);
  check('zip скачан', /-v\d+\.zip$/.test(download.suggestedFilename()) && zip.subarray(0, 2).toString() === 'PK', download.suggestedFilename());
});

await shot('99-final');
// Сеть браузера: 4xx/5xx от api, которые фронт показал как ошибку, видны в errors через уведомление
const notice = await page.locator('.notification .error').innerText().catch(() => '');
check('нет уведомлений об ошибке', !notice, notice);
check('нет ошибок страницы', errors.length === 0, errors.slice(0, 3).join(' | '));
await browser.close();
console.log(failed ? `\n${failed} провал(ов), скриншоты — ${OUT}` : '\nпуть жюри через UI пройден');
annotate(failed ? 'error' : 'notice', failed ? `${failed} провал(ов)` : 'пройден целиком');
process.exit(failed ? 1 : 0);
