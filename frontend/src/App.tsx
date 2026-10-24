import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, ApiRequestError } from './api/client';
import type { Diagnostics, Finding, Graph, GraphNode, ProjectState, RunResult, SourceRange, VerifyReport } from './api/types';
import { AnalysisPanel } from './components/AnalysisPanel';
import { BottomPanel } from './components/BottomPanel';
import { CodeEditor } from './components/CodeEditor';
import { GraphCanvas } from './components/GraphCanvas';
import { findingsByNode, nodeAtLine, nodeForFinding } from './flow/sourceMap';

export function App() {
  const [project, setProject] = useState<ProjectState | null>(null);
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [graph, setGraph] = useState<Graph | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  const [selected, setSelected] = useState<GraphNode | null>(null);
  const [highlight, setHighlight] = useState<SourceRange | null>(null);
  /** Запрос «покажи узел на холсте» — когда выбор пришёл не с самого холста. */
  const [focus, setFocus] = useState<{ id: string; seq: number } | null>(null);
  const [run, setRun] = useState<RunResult | null>(null);
  const [verify, setVerify] = useState<VerifyReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const nodeFindings = useMemo(
    () => (graph && diagnostics ? findingsByNode(graph, diagnostics.findings) : undefined),
    [graph, diagnostics],
  );

  const guard = useCallback(async (action: () => Promise<void>) => {
    try {
      setError(null);
      await action();
    } catch (e) {
      setError(e instanceof ApiRequestError ? `${e.body.error}: ${e.message}` : String(e));
    }
  }, []);

  /** Новый граф: id узлов пересчитаны (graph.schema.json), старое выделение и фокус не имеют смысла. */
  const showGraph = useCallback((g: Graph) => {
    setGraph(g);
    setSelected(null);
    setFocus(null);
  }, []);

  const loadProject = useCallback(async (state: ProjectState) => {
    setProject(state);
    const file = state.files[0]?.path ?? null;
    setActiveFile(file);
    setDraft(state.files[0]?.content ?? '');
    showGraph(await api.getGraph(state.id));
    setDiagnostics(await api.getDiagnostics(state.id));
  }, [showGraph]);

  useEffect(() => {
    if (import.meta.env.VITE_USE_MOCKS === 'true') {
      guard(async () => loadProject(await api.getProject('mock')));
    }
  }, [guard, loadProject]);

  const importFiles = (files: FileList | null) =>
    guard(async () => {
      if (!files?.length) return;
      const sources = await Promise.all(Array.from(files).map(async (f) => ({ path: f.name, content: await f.text() })));
      await loadProject(await api.createProject(sources, sources[0].path));
    });

  const applySource = () =>
    guard(async () => {
      if (!project || !activeFile) return;
      const files = project.files.map((f) => (f.path === activeFile ? { ...f, content: draft } : f));
      await loadProject(await api.updateSource(project.id, project.version, files));
    });

  const selectNode = (node: GraphNode) => {
    setSelected(node);
    setHighlight(node.range ?? null);
  };

  const focusNode = (node: GraphNode) => setFocus((f) => ({ id: node.id, seq: (f?.seq ?? 0) + 1 }));

  const selectFinding = (finding: Finding) => {
    if (finding.file !== activeFile) {
      const file = project?.files.find((f) => f.path === finding.file);
      if (file) {
        setActiveFile(file.path);
        setDraft(file.content);
      }
    }
    setHighlight({ file: finding.file, startLine: finding.startLine, endLine: finding.endLine });
    const node = graph && nodeForFinding(graph, finding);
    setSelected(node ?? null);
    if (node) focusNode(node);
  };

  /** Строка → узел. Пока правка не применена, номера строк в редакторе и в графе расходятся — не сопоставляем. */
  const savedContent = project?.files.find((f) => f.path === activeFile)?.content;
  const selectLine = (line: number) => {
    if (!graph || !activeFile || draft !== savedContent) return;
    const node = nodeAtLine(graph, activeFile, line);
    setSelected(node);
    setHighlight(node?.range ?? null);
    if (node) focusNode(node);
  };

  return (
    <div className="app">
      <header className="toolbar">
        <strong>Визуальная среда программирования</strong>
        <label className="button">
          Импорт Java
          <input type="file" accept=".java" multiple hidden onChange={(e) => importFiles(e.target.files)} />
        </label>
        <button disabled={!project} onClick={applySource}>Применить код</button>
        <button disabled={!project} onClick={() => project && guard(async () => showGraph(await api.getGraph(project.id)))}>Обзор</button>
        <button disabled={!project} onClick={() => project && guard(async () => setRun(await api.run(project.id)))}>▶ Запуск</button>
        <button disabled={!project} onClick={() => project && guard(async () => setVerify(await api.verify(project.id)))}>Проверить</button>
        {project && <span className="muted">v{project.version}</span>}
        {error && <span className="error">{error}</span>}
      </header>

      <aside className="sidebar">
        <h3>Проект</h3>
        {project?.files.map((f) => (
          <div key={f.path} className={`file ${f.path === activeFile ? 'active' : ''}`}
               onClick={() => { setActiveFile(f.path); setDraft(f.content); }}>
            {f.path}
          </div>
        ))}
        {selected && (
          <div className="selected-info">
            <h3>Узел</h3>
            <div>{selected.label} <span className="muted">({selected.kind})</span></div>
            {selected.code && <code>{selected.code}</code>}
          </div>
        )}
      </aside>

      <main className="canvas">
        {graph ? (
          <GraphCanvas
            graph={graph}
            selectedId={selected?.id ?? null}
            onSelect={selectNode}
            onOpenMethod={(node) => project && guard(async () => showGraph(await api.getGraph(project.id, 'method', node.id)))}
            focus={focus}
            findings={nodeFindings}
          />
        ) : (
          <div className="empty">Импортируйте .java-файл, чтобы построить граф</div>
        )}
      </main>

      <aside className="analysis-panel">
        <AnalysisPanel diagnostics={diagnostics} onSelect={selectFinding} />
      </aside>

      <section className="code">
        <CodeEditor value={draft} highlight={highlight} onChange={setDraft} onCursorLine={selectLine} />
      </section>

      <section className="output">
        <BottomPanel run={run} verify={verify} />
      </section>
    </div>
  );
}
