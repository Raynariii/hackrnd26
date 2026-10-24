import { test } from 'node:test';
import assert from 'node:assert/strict';
import { diffLines } from './diff.ts';

test('один правленый файл — без заголовка, как раньше', () => {
  const lines = diffLines('--- a/Main.java\n+++ b/Main.java\n@@ -1,2 +1,2 @@\n-a\n+b\n c\n');
  assert.deepEqual(lines.map((l) => l.kind), ['hunk', 'del', 'add', 'ctx']);
});

test('несколько файлов и новый — заголовки с именами, новый помечен', () => {
  const lines = diffLines('--- a/Main.java\n+++ b/Main.java\n@@ -1 +1 @@\n-x\n+y\n--- /dev/null\n+++ b/Inventory.java\n@@ -0,0 +1 @@\n+class Inventory {}\n');
  assert.deepEqual(lines.filter((l) => l.kind === 'file').map((l) => l.text), ['Main.java', 'Inventory.java — новый файл']);
  assert.equal(lines[0].kind, 'file');
  assert.equal(lines.at(-1)!.text, '+class Inventory {}');
});

test('удалённый файл помечен', () => {
  const lines = diffLines('--- a/Old.java\n+++ /dev/null\n@@ -1 +0,0 @@\n-class Old {}\n');
  assert.deepEqual(lines[0], { kind: 'file', text: 'Old.java — удалён' });
});
