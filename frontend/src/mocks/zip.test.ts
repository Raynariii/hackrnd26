import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { storedZip } from './zip.ts';

test('storedZip читается python zipfile: имена в UTF-8, содержимое и CRC сходятся', () => {
  const dir = mkdtempSync(join(tmpdir(), 'zip-'));
  const path = join(dir, 'a.zip');
  writeFileSync(path, storedZip([{ name: 'demo/README.md', content: 'Проверка: 6/6' }, { name: 'demo/A.java', content: 'class A {}' }]));
  const out = execFileSync('python3', ['-c', `import zipfile,sys;z=zipfile.ZipFile(sys.argv[1]);assert z.testzip() is None;print(z.namelist());print(z.read('demo/README.md').decode())`, path]).toString();
  assert.match(out, /\['demo\/README.md', 'demo\/A.java'\]/);
  assert.match(out, /Проверка: 6\/6/);
});
