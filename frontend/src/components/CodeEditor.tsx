import { useEffect, useRef } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import type { SourceRange } from '../api/types';

type MonacoEditor = Parameters<OnMount>[0];

interface Props {
  value: string;
  highlight: SourceRange | null;
  onChange: (value: string) => void;
}

export function CodeEditor({ value, highlight, onChange }: Props) {
  const editorRef = useRef<MonacoEditor | null>(null);
  const decorations = useRef<string[]>([]);

  useEffect(() => {
    const editor = editorRef.current;
    if (!editor) return;
    decorations.current = editor.deltaDecorations(
      decorations.current,
      highlight
        ? [{
            range: { startLineNumber: highlight.startLine, startColumn: 1, endLineNumber: highlight.endLine, endColumn: 1 },
            options: { isWholeLine: true, className: 'code-highlight' },
          }]
        : [],
    );
    if (highlight) editor.revealLineInCenter(highlight.startLine);
  }, [highlight]);

  return (
    <Editor
      language="java"
      theme="vs-dark"
      value={value}
      onMount={(editor) => {
        editorRef.current = editor;
      }}
      onChange={(v) => onChange(v ?? '')}
      options={{ minimap: { enabled: false }, fontSize: 13, automaticLayout: true }}
    />
  );
}
