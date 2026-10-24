import { test } from 'node:test';
import assert from 'node:assert/strict';
import { exportSummary, fileNameFrom, readExportHeaders } from './download.ts';

test('fileNameFrom: как отдаёт Spring ContentDisposition', () => {
  assert.equal(fileNameFrom('attachment; filename="OrdersMonolith-v4.zip"', 'x.zip'), 'OrdersMonolith-v4.zip');
  assert.equal(fileNameFrom("attachment; filename*=UTF-8''%D0%B7%D0%B0%D0%BA%D0%B0%D0%B7%D1%8B-v2.zip", 'x.zip'), 'заказы-v2.zip');
  assert.equal(fileNameFrom('attachment; filename=plain.zip', 'x.zip'), 'plain.zip');
  assert.equal(fileNameFrom(null, 'x.zip'), 'x.zip');
  assert.equal(fileNameFrom('attachment', 'x.zip'), 'x.zip');
});

test('readExportHeaders и подпись', () => {
  const h = new Headers({ 'Content-Disposition': 'attachment; filename="a-v3.zip"', 'X-Verify-Passed': '9/9', 'X-Behavior-Preserved': 'true' });
  const r = readExportHeaders(h, new Blob(['x']), 'p.zip');
  assert.deepEqual([r.fileName, r.passed, r.behaviorPreserved], ['a-v3.zip', '9/9', true]);
  assert.equal(exportSummary(r.passed, r.behaviorPreserved), '9/9 тестов, поведение сохранено');
  assert.equal(exportSummary('2/9', false), '2/9 тестов, поведение изменилось');
  assert.equal(exportSummary(null, null), '');
  assert.equal(readExportHeaders(new Headers(), new Blob([]), 'p.zip').behaviorPreserved, null);
});
