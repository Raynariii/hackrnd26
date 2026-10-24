/** Строка унифицированного диффа для показа: заголовок файла, добавлено, удалено, ханк или контекст. */
export type DiffLine = { kind: 'file' | 'add' | 'del' | 'hunk' | 'ctx'; text: string };

/**
 * Унифицированный дифф (UnifiedDiff api) → строки для показа. Пары `--- a/X` / `+++ b/X` превращаются в один
 * заголовок файла — после разбиения на классы изменений несколько файлов, и новые надо отличать от правленых.
 * Заголовок пишется, только если файлов больше одного или файл новый/удалён: для одного правленого файла он лишний.
 */
export function diffLines(diff: string): DiffLine[] {
  const raw = diff.replace(/\n$/, '').split('\n');
  const files: DiffLine[] = [];
  const out: DiffLine[] = [];
  let from: string | null = null;
  for (const l of raw) {
    if (l.startsWith('--- ')) { from = l.slice(4).replace(/^a\//, ''); continue; }
    if (l.startsWith('+++ ')) {
      const to = l.slice(4).replace(/^b\//, '');
      const text = from === '/dev/null' ? `${to} — новый файл` : to === '/dev/null' ? `${from} — удалён` : to;
      const header: DiffLine = { kind: 'file', text };
      files.push(header);
      out.push(header);
      from = null;
      continue;
    }
    out.push({ kind: l.startsWith('@@') ? 'hunk' : l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : 'ctx', text: l });
  }
  const keepHeaders = files.length > 1 || files.some((f) => f.text.includes(' — '));
  return keepHeaders ? out : out.filter((l) => l.kind !== 'file');
}
