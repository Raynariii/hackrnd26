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
      theme="blueprints"
      beforeMount={(monaco) => {
        // Лист как у холста: белый фон, графитовый текст, кобальтовое выделение; цвета синтаксиса — светлой темы vs.
        monaco.editor.defineTheme('blueprints', {
          base: 'vs',
          inherit: true,
          rules: [{ token: 'comment', foreground: '6b7584', fontStyle: 'italic' }],
          colors: {
            'editor.background': '#ffffff',
            'editor.foreground': '#18202b',
            'editor.lineHighlightBackground': '#f5f6f8',
            'editor.lineHighlightBorder': '#00000000',
            'editorLineNumber.foreground': '#a3abb7',
            'editorLineNumber.activeForeground': '#465163',
            'editor.selectionBackground': '#d6e0f3',
            'editorIndentGuide.background1': '#eef0f4',
            'editorCursor.foreground': '#2350a8',
          },
        });
      }}
      value={value}
      onMount={(editor) => {
        editorRef.current = editor;
        applyHighlight(editor, highlight);
        editor.onDidChangeCursorPosition((e) => {
          if (e.reason === monacoEditor.CursorChangeReason.Explicit) cursorCallback.current?.(e.position.lineNumber);
        });
      }}
      onChange={(v) => onChange(v ?? '')}
      options={{ minimap: { enabled: false }, fontSize: 13, lineHeight: 20, fontFamily: "'JetBrains Mono', ui-monospace, monospace", automaticLayout: true, padding: { top: 12 }, renderLineHighlight: 'line', scrollBeyondLastLine: false }}
    />
  );
}
