import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, ApiRequestError } from './api/client';
import type { Diagnostics, Finding, Graph, GraphNode, GraphOpRequest, ProjectState, RunResult, SourceFile, SourceRange, VerifyReport } from './api/types';
import { AnalysisPanel, type AppliedFix } from './components/AnalysisPanel';
import { BottomPanel } from './components/BottomPanel';
import { CodeEditor } from './components/CodeEditor';
import { GraphCanvas } from './components/GraphCanvas';
import { NodeInspector } from './components/NodeInspector';
import { VerifyScreen } from './components/VerifyScreen';
import { changedLineNumbers, findMethodAgain, firstChangedLine } from './flow/edit';
import { findingsByNode, nodeAtLine, nodeForFinding } from './flow/sourceMap';

/** Общий диапазон строк выделения (в одном файле) — для подсветки в коде. */
function unionRange(nodes: GraphNode[]): SourceRange | null {
  const ranges = nodes.map((n) => n.range).filter((r): r is SourceRange => !!r);
  if (!ranges.length || ranges.some((r) => r.file !== ranges[0].file)) return ranges.at(-1) ?? null;
  return {
    file: ranges[0].file,
    startLine: Math.min(...ranges.map((r) => r.startLine)),
    endLine: Math.max(...ranges.map((r) => r.endLine)),
  };
}

export function App() {
  const [project, setProject] = useState<ProjectState | null>(null);
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [graph, setGraph] = useState<Graph | null>(null);
  /** Последний обзорный граф: по нему после правки находим открытый метод заново (id пересчитываются). */
  const [overview, setOverview] = useState<Graph | null>(null);
  const [diagnostics, setDiagnostics] = useState<Diagnostics | null>(null);
  /** Выделенные блоки; последний — основной (его показывает инспектор и подсвечивает код). */
  const [selection, setSelection] = useState<GraphNode[]>([]);
  const selected = selection.at(-1) ?? null;
  const setSelected = useCallback((node: GraphNode | null) => setSelection(node ? [node] : []), []);
  const [highlight, setHighlight] = useState<SourceRange | null>(null);
  /** Запрос «покажи узел на холсте» — когда выбор пришёл не с самого холста. */
  const [focus, setFocus] = useState<{ id: string; seq: number } | null>(null);
  const [run, setRun] = useState<RunResult | null>(null);
  /** stdin для «Запуска»: программы со Scanner читают ввод; сервер запоминает его и для дифф-тестов verify. */
  const [stdin, setStdin] = useState('');
  const [running, setRunning] = useState(false);
  const [verify, setVerify] = useState<VerifyReport | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [showVerify, setShowVerify] = useState(false);
  /** Последнее авто-исправление и файлы до него — для «Отменить» (PUT /source со старым текстом). */
  const [applied, setApplied] = useState<AppliedFix | null>(null);
  const [beforeFix, setBeforeFix] = useState<SourceFile[] | null>(null);
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
    const ov = await api.getGraph(state.id);
    setOverview(ov);
    showGraph(ov);
    setDiagnostics(await api.getDiagnostics(state.id));
  }, [showGraph]);

  /**
   * Проект изменился (правка кода или операция графа): перечитать обзор, остаться в открытом методе,
   * выделить узел на первой изменившейся строке и показать его в коде и на холсте.
   */
  const refreshAfterChange = async (prev: ProjectState, next: ProjectState, selectChanged: boolean) => {
    setProject(next);
    const changed = firstChangedLine(prev.files, next.files);
    const file = changed?.file ?? (next.files.some((f) => f.path === activeFile) ? activeFile : next.files[0]?.path ?? null);
    setActiveFile(file);
    setDraft(next.files.find((f) => f.path === file)?.content ?? '');

    const newOverview = await api.getGraph(next.id);
    setOverview(newOverview);
    let target = newOverview;
    if (graph?.level === 'method' && graph.scope && overview) {
      const methodId = findMethodAgain(overview, graph.scope, newOverview);
      if (methodId) target = await api.getGraph(next.id, 'method', methodId);
    }
    showGraph(target);
    setDiagnostics(await api.getDiagnostics(next.id));

    if (changed) {
      // Первая изменённая строка, которая есть в открытом графе: правка может начаться вне метода
      // (константа в начале класса после авто-исправления), а выделить надо блок в методе.
      const oldText = prev.files.find((f) => f.path === changed.file)?.content ?? '';
      const newText = next.files.find((f) => f.path === changed.file)?.content ?? '';
      const node = selectChanged
        ? changedLineNumbers(oldText, newText).map((l) => nodeAtLine(target, changed.file, l)).find(Boolean) ?? null
        : null;
      setSelected(node);
      setHighlight(node?.range ?? { file: changed.file, startLine: changed.line, endLine: changed.line });
      if (node) setFocus((f) => ({ id: node.id, seq: (f?.seq ?? 0) + 1 }));
    }
  };

  /** Правка на графе: ошибка ядра (422) возвращается текстом в инспектор, а не в шапку. */
  const applyGraphOp = async (req: Omit<GraphOpRequest, 'version'>): Promise<string | null> => {
    if (!project) return 'Нет проекта';
    try {
      const next = await api.graphOp(project.id, { ...req, version: project.version });
      await refreshAfterChange(project, next, req.op !== 'delete');
      return null;
    } catch (e) {
      if (e instanceof ApiRequestError) return e.status === 409 ? 'Проект изменился в другой вкладке — обновите страницу' : e.message;
      return String(e);
    }
  };

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
      await refreshAfterChange(project, await api.updateSource(project.id, project.version, files), true);
    });

  const runProgram = () =>
    guard(async () => {
      if (!project) return;
      setRunning(true);
      try {
        setRun(await api.run(project.id, stdin));
      } finally {
        setRunning(false);
      }
    });

  /** Дифф-тесты версия 1 vs текущая + замер времени; отчёт открывается поверх холста. */
  const runVerify = () =>
    guard(async () => {
      if (!project) return;
      setVerifying(true);
      try {
        setVerify(await api.verify(project.id, { fuzz: true, benchmark: true }));
        setShowVerify(true);
      } finally {
        setVerifying(false);
      }
    });

  /**
   * Находка → «Исправить автоматически» → новый код и граф → сразу дифф-тесты и замер (PLAN.md §1, п. 4):
   * оптимизация считается сделанной только при зелёной проверке поведения.
   */
  const applyFix = async (finding: Finding): Promise<string | null> => {
    if (!project) return 'Нет проекта';
    try {
      const prev = project;
      const result = await api.applyFix(project.id, finding.id, project.version);
      await refreshAfterChange(prev, result.state, true);
      setApplied({
        title: finding.title,
        // описание от сервера (что именно сделал FixEngine), иначе — предложенное анализатором
        description: result.description ?? finding.fix?.description ?? '',
        diff: result.diff,
        gate: result.verify ?? null,
      });
      setBeforeFix(prev.files);
      void runVerify();
      return null;
    } catch (e) {
      if (e instanceof ApiRequestError) return e.status === 409 ? 'Код изменился — диагностика устарела, она перечитана' : e.message;
      return String(e);
    }
  };

  const undoFix = () =>
    guard(async () => {
      if (!project || !beforeFix) return;
      await refreshAfterChange(project, await api.updateSource(project.id, project.version, beforeFix), true);
      setApplied(null);
      setBeforeFix(null);
    });

  /** Клик по блоку; с Shift/Ctrl/Cmd — добавить в выделение или убрать из него (для операций над несколькими). */
  const selectNode = (node: GraphNode, additive = false) => {
    if (additive && selection.length > 0) {
      const next = selection.some((n) => n.id === node.id) ? selection.filter((n) => n.id !== node.id) : [...selection, node];
      setSelection(next);
      setHighlight(unionRange(next));
      return;
    }
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
        <button disabled={!project} onClick={() => project && guard(async () => {
          const ov = await api.getGraph(project.id);
          setOverview(ov);
          showGraph(ov);
        })}>Обзор</button>
        <button disabled={!project || running} onClick={runProgram}>{running ? 'Запуск…' : '▶ Запуск'}</button>
        <button disabled={!project || verifying} onClick={runVerify}>{verifying ? 'Проверяем…' : 'Проверить'}</button>
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
        {selected && graph && <NodeInspector selection={selection} graph={graph} onOp={applyGraphOp} />}
        {graph?.level === 'method' && <div className="muted hint">Shift/Ctrl+клик — выделить несколько блоков</div>}
      </aside>

      <main className="canvas">
        {graph ? (
          <GraphCanvas
            graph={graph}
            selectedIds={selection.map((n) => n.id)}
            onSelect={selectNode}
            onOpenMethod={(node) => project && guard(async () => showGraph(await api.getGraph(project.id, 'method', node.id)))}
            focus={focus}
            findings={nodeFindings}
          />
        ) : (
          <div className="empty">Импортируйте .java-файл, чтобы построить граф</div>
        )}
        {showVerify && verify && (
          <VerifyScreen report={verify} running={verifying} onRerun={runVerify} onClose={() => setShowVerify(false)} />
        )}
      </main>

      <aside className="analysis-panel">
        <AnalysisPanel
          diagnostics={diagnostics}
          onSelect={selectFinding}
          onFix={applyFix}
          applied={applied}
          onVerify={runVerify}
          onUndo={undoFix}
          onDismissApplied={() => setApplied(null)}
          verifying={verifying}
        />
      </aside>

      <section className="code">
        <CodeEditor value={draft} highlight={highlight} onChange={setDraft} onCursorLine={selectLine} />
      </section>

      <section className="output">
        <BottomPanel run={run} verify={verify} onOpenReport={() => setShowVerify(true)}
          stdin={stdin} onStdin={setStdin} onRun={runProgram} running={running} canRun={!!project} />
      </section>
    </div>
  );
}
