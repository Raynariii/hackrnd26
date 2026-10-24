import { useEffect, useRef } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { editor as monacoEditor } from 'monaco-editor';
import type { SourceRange } from '../api/types';

type MonacoEditor = Parameters<OnMount>[0];

interface Props {
  value: string;
  highlight: SourceRange | null;
  onChange: (value: string) => void;
  /** Пользователь сам поставил курсор на строку (клик, стрелки, переход) — не при наборе текста. */
  onCursorLine?: (line: number) => void;
}

export function CodeEditor({ value, highlight, onChange, onCursorLine }: Props) {
  const editorRef = useRef<MonacoEditor | null>(null);
  const decorations = useRef<monacoEditor.IEditorDecorationsCollection | null>(null);
  // Колбэк в ref: подписка на курсор создаётся один раз при монтировании редактора.
  const cursorCallback = useRef(onCursorLine);
  cursorCallback.current = onCursorLine;

  const applyHighlight = (editor: MonacoEditor, range: SourceRange | null) => {
    decorations.current ??= editor.createDecorationsCollection();
    decorations.current.set(
      range
        ? [{
            range: { startLineNumber: range.startLine, startColumn: 1, endLineNumber: range.endLine, endColumn: 1 },
            options: { isWholeLine: true, className: 'code-highlight', linesDecorationsClassName: 'code-highlight-gutter' },
          }]
        : [],
    );
    // Не дёргаем код, если подсвеченный фрагмент уже на экране (например, выбор пришёл из самого редактора).
    if (range) {
      editor.revealRangeInCenterIfOutsideViewport({
        startLineNumber: range.startLine, startColumn: 1, endLineNumber: range.startLine, endColumn: 1,
      });
    }
  };

  useEffect(() => {
    if (editorRef.current) applyHighlight(editorRef.current, highlight);
  }, [highlight]);

  return (
    <Editor
      language="java"
      theme="vs-dark"
      value={value}
      onMount={(editor) => {
        editorRef.current = editor;
        applyHighlight(editor, highlight);
        editor.onDidChangeCursorPosition((e) => {
          if (e.reason === monacoEditor.CursorChangeReason.Explicit) cursorCallback.current?.(e.position.lineNumber);
        });
      }}
      onChange={(v) => onChange(v ?? '')}
      options={{ minimap: { enabled: false }, fontSize: 13, automaticLayout: true }}
    />
  );
}
