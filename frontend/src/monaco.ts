// Monaco из пакета, а не с CDN (по умолчанию @monaco-editor/react грузит его с jsdelivr):
// демо должно работать без интернета (LEAD.md, G10). Импортируется один раз из main.tsx.
import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';

// Подсветка Java — на токенизаторе, ей хватает базового воркера редактора.
self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

loader.config({ monaco });
