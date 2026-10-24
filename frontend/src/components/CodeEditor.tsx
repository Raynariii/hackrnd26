import { useEffect, useRef } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { editor as monacoEditor, KeyCode, KeyMod } from 'monaco-editor';
import type { SourceRange } from '../api/types';

type MonacoEditor = Parameters<OnMount>[0];

interface Props {
  value: string;
  highlight: SourceRange | null;
  onChange: (value: string) => void;
  /** Пользователь сам поставил курсор на строку (клик, стрелки, переход) — не при наборе текста. */
  onCursorLine?: (line: number) => void;
  /** Ctrl/Cmd+S — применить код (как сохранение в IDE). */
  onSave?: () => void;
  theme?: 'light' | 'dark';
}

/** Цвета синтаксиса — по мотивам IntelliJ Light и Darcula; фон — цвет острова темы. */
function defineThemes(monaco: typeof import('monaco-editor')) {
  monaco.editor.defineTheme('blueprints-light', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: '0033b3' },
      { token: 'string', foreground: '067d17' },
      { token: 'number', foreground: '1750eb' },
      { token: 'comment', foreground: '8c8c8c', fontStyle: 'italic' },
      { token: 'annotation', foreground: '9e880d' },
      { token: 'type', foreground: '000000' },
    ],
    colors: {
      'editor.background': '#ffffff',
      'editor.foreground': '#1e1f22',
      'editor.lineHighlightBackground': '#f5f8fe',
      'editor.lineHighlightBorder': '#00000000',
      'editorLineNumber.foreground': '#aeb3c2',
      'editorLineNumber.activeForeground': '#5f6370',
      'editor.selectionBackground': '#a6d2ff80',
      'editorIndentGuide.background1': '#ebecf0',
      'editorCursor.foreground': '#1e1f22',
      'editorGutter.background': '#ffffff',
    },
  });
  monaco.editor.defineTheme('blueprints-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword', foreground: 'cf8e6d' },
      { token: 'string', foreground: '6aab73' },
      { token: 'number', foreground: '2aacb8' },
      { token: 'comment', foreground: '7a7e85', fontStyle: 'italic' },
      { token: 'annotation', foreground: 'b3ae60' },
      { token: 'type', foreground: 'bcbec4' },
    ],
    colors: {
      'editor.background': '#1f2023',
      'editor.foreground': '#bcbec4',
      'editor.lineHighlightBackground': '#26282e',
      'editor.lineHighlightBorder': '#00000000',
      'editorLineNumber.foreground': '#4b5059',
      'editorLineNumber.activeForeground': '#a1a3ab',
      'editor.selectionBackground': '#214283',
      'editorIndentGuide.background1': '#2f3135',
      'editorCursor.foreground': '#ced0d6',
      'editorGutter.background': '#1f2023',
    },
  });
}

export function CodeEditor({ value, highlight, onChange, onCursorLine, onSave, theme = 'light' }: Props) {
  const editorRef = useRef<MonacoEditor | null>(null);
  const decorations = useRef<monacoEditor.IEditorDecorationsCollection | null>(null);
  // Колбэк в ref: подписка на курсор создаётся один раз при монтировании редактора.
  const cursorCallback = useRef(onCursorLine);
  cursorCallback.current = onCursorLine;
  const saveCallback = useRef(onSave);
  saveCallback.current = onSave;

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
      theme={theme === 'dark' ? 'blueprints-dark' : 'blueprints-light'}
      beforeMount={defineThemes}
      value={value}
      onMount={(editor) => {
        editorRef.current = editor;
        applyHighlight(editor, highlight);
        editor.addCommand(KeyMod.CtrlCmd | KeyCode.KeyS, () => saveCallback.current?.());
        editor.onDidChangeCursorPosition((e) => {
          if (e.reason === monacoEditor.CursorChangeReason.Explicit) cursorCallback.current?.(e.position.lineNumber);
        });
      }}
      onChange={(v) => onChange(v ?? '')}
      options={{ minimap: { enabled: false }, fontSize: 13, lineHeight: 20, fontFamily: "'JetBrains Mono', ui-monospace, monospace", automaticLayout: true, padding: { top: 10 }, renderLineHighlight: 'line', scrollBeyondLastLine: false, lineNumbersMinChars: 4, scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 } }}
    />
  );
}
