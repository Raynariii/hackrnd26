import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { Box, ChevronRight, ChevronsLeft, ChevronsRight, CircleAlert, CircleCheck, CircleX, Download, DraftingCompass, FileCode2, FlaskConical, FolderOpen, LayoutGrid, LoaderCircle, Moon, PanelRightOpen, Play, ShieldCheck, SquareFunction, Sun, Terminal, TriangleAlert, Upload, Workflow, X } from 'lucide-react';
import { api, ApiRequestError, isUnsupported } from './api/client';
import { applyRunEvent, STREAM_START } from './flow/run';
import type { DecomposeResult, Diagnostics, Finding, Graph, GraphNode, GraphOpRequest, NodeKind, ProjectState, RunResult, SourceFile, SourceRange, VerifyReport } from './api/types';
import { AnalysisPanel, MetricsPanel, type AdviceReply, type AppliedFix } from './components/AnalysisPanel';
import { DecomposePanel } from './components/DecomposePanel';
import { exportSummary } from './api/download';
import { BottomPanel, type BottomTab } from './components/BottomPanel';
import { CodeEditor } from './components/CodeEditor';
import { GraphCanvas } from './components/GraphCanvas';
import { NodeInspector } from './components/NodeInspector';
import { Explorer } from './components/Explorer';
import { BlockPalette } from './components/BlockPalette';
import { allowedOps } from './flow/edit';
import { VerifyScreen } from './components/VerifyScreen';
import { changedLineNumbers, findMethodAgain, firstChangedLine } from './flow/edit';
import { findingsByNode, nodeAtLine, nodeForFinding } from './flow/sourceMap';

type Theme = 'light' | 'dark';
type RightTab = 'problems' | 'metrics' | 'decompose';
/** Раскладка центра: граф и код, только граф (для показа) или только код. */
type ViewMode = 'split' | 'graph' | 'code';
interface Panels { left: boolean; right: boolean; bottom: boolean }

/** Настройки вида живут в localStorage браузера; недоступен (приватный режим) — работаем с умолчаниями. */
function stored<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}
function store(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* без сохранения настроек */
  }
}

/** Кнопка полосы инструментов (как tool window stripe в IDE): открывает и прячет панель. */
function StripeButton({ icon, label, active, badge, onClick, disabled, title }: { icon: React.ReactNode; label: string; active?: boolean; badge?: number; onClick: () => void; disabled?: boolean; title?: string }) {
  return (
    <button className="stripe-btn" aria-pressed={active} title={title ?? label} aria-label={label} onClick={onClick} disabled={disabled}>
      {icon}
      {!!badge && <span className="stripe-badge">{badge > 99 ? '99+' : badge}</span>}
    </button>
  );
}

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
  const [decomp, setDecomp] = useState<{ version: number; result: DecomposeResult } | null>(null);
  const [decomposing, setDecomposing] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [exported, setExported] = useState<{ fileName: string; summary: string; preserved: boolean | null } | null>(null);
  const [activeClass, setActiveClass] = useState<string | null>(null);
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
  /**
   * Эндпоинты из PR роли 4, которых может не быть на сервере (#40 advice, #42 complexity, #55 decompose apply,
   * #56 run/stream): первый ответ «нет такого» прячет функцию до перезагрузки, остальное работает как раньше.
   */
  const [unsupported, setUnsupported] = useState<Set<'advice' | 'complexity' | 'decomposeApply' | 'runStream'>>(new Set());
  const markUnsupported = useCallback((f: 'advice' | 'complexity' | 'decomposeApply' | 'runStream') =>
    setUnsupported((s) => (s.has(f) ? s : new Set(s).add(f))), []);
  /** Big O выделенных блоков блок-схемы (POST /complexity); key — выделение и версия, к которым относится ответ. */
  const [selComplexity, setSelComplexity] = useState<{ key: string; value: string | null } | null>(null);
  const [applyingDecomp, setApplyingDecomp] = useState(false);
  /** Палитра → инспектор: открыть «Вставить после» с выбранным видом блока. */
  const [insertIntent, setInsertIntent] = useState<{ kind: NodeKind; seq: number } | null>(null);
  const [theme, setTheme] = useState<Theme>(() => stored<Theme>('jb.theme', 'dark'));
  const [panels, setPanels] = useState<Panels>(() => stored<Panels>('jb.panels', { left: true, right: true, bottom: false }));
  const [rightTab, setRightTab] = useState<RightTab>('problems');
  const [bottomTab, setBottomTab] = useState<BottomTab>('console');
  /** Доля высоты холста в центральной колонке (остальное — редактор); граница перетаскивается. */
  const [split, setSplit] = useState(() => stored<number>('jb.split', 0.6));
  const [view, setView] = useState<ViewMode>(() => stored<ViewMode>('jb.view', 'split'));
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const centerRef = useRef<HTMLDivElement>(null);

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

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    store('jb.theme', theme);
  }, [theme]);
  useEffect(() => store('jb.panels', panels), [panels]);
  useEffect(() => store('jb.split', split), [split]);
  useEffect(() => store('jb.view', view), [view]);

  const togglePanel = (key: keyof Panels, force?: boolean) => setPanels((p) => ({ ...p, [key]: force ?? !p[key] }));
  /** Кнопка полосы: открыть панель на нужной вкладке; повторное нажатие на активную — спрятать. */
  /** Показать правую панель на нужной вкладке (сворачивается кнопкой в её шапке). */
  const openRight = (tab: RightTab) => {
    setRightTab(tab);
    togglePanel('right', true);
  };
  const openBottom = (tab: BottomTab) => {
    if (panels.bottom && bottomTab === tab) togglePanel('bottom', false);
    else {
      setBottomTab(tab);
      togglePanel('bottom', true);
    }
  };

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

  /** Запуск: вывод идёт в консоль по мере появления (SSE); сервер без стрима — обычный run целиком. */
  const runProgram = () =>
    guard(async () => {
      if (!project) return;
      setRunning(true);
      setBottomTab('console');
      togglePanel('bottom', true);
      try {
        let streamed = false;
        if (!unsupported.has('runStream')) {
          let acc = STREAM_START;
          setRun(acc);
          streamed = await api.runStream(project.id, stdin, (e) => {
            acc = applyRunEvent(acc, e);
            setRun(acc);
          });
          if (!streamed) markUnsupported('runStream');
        }
        if (!streamed) setRun(await api.run(project.id, stdin));
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

  /** Zip с текущим кодом и JUnit golden-master тестами против версии 1 — сдаётся как «автотесты и результат». */
  const exportProject = () =>
    guard(async () => {
      if (!project) return;
      setExporting(true);
      try {
        const file = await api.exportProject(project.id);
        const url = URL.createObjectURL(file.blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = file.fileName;
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 10_000);
        setExported({ fileName: file.fileName, summary: exportSummary(file.passed, file.behaviorPreserved), preserved: file.behaviorPreserved });
      } finally {
        setExporting(false);
      }
    });

  const proposeDecomposition = () =>
    guard(async () => {
      if (!project) return;
      setDecomposing(true);
      try {
        setDecomp({ version: project.version, result: await api.decompose(project.id, project.version) });
        setActiveClass(null);
      } finally {
        setDecomposing(false);
      }
    });

  /**
   * «Применить разбиение»: сервер переносит static-методы и поля в новые классы на копии, прогоняет quality gate
   * (компиляция + дифф-тесты против текущей версии) и только при зелёном коммитит новую версию. Дальше — как
   * после авто-исправления: дифф, отчёт gate, «Отменить» и полная проверка против версии 1.
   */
  const applyDecomposition = async (): Promise<string | null> => {
    if (!project) return 'Нет проекта';
    setApplyingDecomp(true);
    try {
      const prev = project;
      const res = await api.applyDecomposition(project.id, project.version);
      const change = res.result;
      if (!change) return 'Сервер не вернул новую версию — разбиение не применено';
      await refreshAfterChange(prev, change.state, false);
      const added = change.state.files.filter((f) => !prev.files.some((p) => p.path === f.path)).map((f) => f.path);
      setApplied({
        kind: 'decompose',
        title: added.length ? `Новые классы: ${added.map((f) => f.replace(/\.java$/, '')).join(', ')}` : 'Монолит разбит на классы',
        description: change.description ?? (res.notes ?? []).join(' '),
        diff: change.diff,
        gate: change.verify ?? null,
      });
      setBeforeFix(prev.files);
      setDecomp(null);
      setActiveClass(null);
      void runVerify();
      return null;
    } catch (e) {
      if (isUnsupported(e)) {
        markUnsupported('decomposeApply');
        return null;
      }
      if (e instanceof ApiRequestError) return e.status === 409 ? 'Код изменился — пересчитайте разбиение' : e.message;
      return String(e);
    } finally {
      setApplyingDecomp(false);
    }
  };

  /** Совет LLM по находке (POST /advice): только текст, применяется обычной правкой кода и проверяется /verify. */
  const askAdvice = async (finding: Finding): Promise<AdviceReply> => {
    if (!project) return { error: 'Нет проекта' };
    try {
      const advice = await api.advice(project.id, finding.id);
      return advice ? { text: advice.suggestion, model: advice.model ?? null } : { off: true };
    } catch (e) {
      if (isUnsupported(e)) {
        markUnsupported('advice');
        return { off: true };
      }
      if (e instanceof ApiRequestError) {
        return { error: e.status === 404 ? 'Находка устарела — диагностика перечитана' : e.status === 502 ? 'Советчик не ответил, попробуйте ещё раз' : e.message };
      }
      return { error: String(e) };
    }
  };

  /** Класс из предложения → его члены выделены на обзоре (открываем обзор, если сейчас блок-схема метода). */
  const showProposedClass = (className: string, nodes: GraphNode[]) => {
    if (!overview || nodes.length === 0) return;
    if (graph !== overview) showGraph(overview);
    setSelection(nodes);
    setActiveClass(className);
    setHighlight(nodes[0].range ?? null);
    focusNode(nodes[0]);
  };

  const showMember = (node: GraphNode) => {
    if (!overview) return;
    if (graph !== overview) showGraph(overview);
    setSelected(node);
    setHighlight(node.range ?? null);
    focusNode(node);
  };

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

  const openMethod = (node: GraphNode) => project && guard(async () => showGraph(await api.getGraph(project.id, 'method', node.id)));
  const openOverview = () => project && guard(async () => {
    const ov = await api.getGraph(project.id);
    setOverview(ov);
    showGraph(ov);
  });
  /** Метод, открытый на холсте, — для вкладки и «хлебных крошек». */
  const openedMethod = graph?.level === 'method' ? overview?.nodes.find((n) => n.id === graph.scope) ?? null : null;
  const ownerType = openedMethod?.parent ? overview?.nodes.find((n) => n.id === openedMethod.parent) ?? null : null;

  /** Проводник → член класса: показать на обзоре, выделить и подсветить код. */
  const selectMember = (node: GraphNode) => {
    if (overview && graph !== overview) showGraph(overview);
    if (node.range && node.range.file !== activeFile) {
      const file = project?.files.find((f) => f.path === node.range!.file);
      if (file) {
        setActiveFile(file.path);
        setDraft(file.content);
      }
    }
    setSelected(node);
    setHighlight(node.range ?? null);
    focusNode(node);
  };

  const canInsertAfter = (node: GraphNode) => !!graph && allowedOps(node, graph.level).has('insertAfter');
  /** Вид блока из палитры: брошен на блок или выбран кликом при выделенном блоке → форма вставки в инспекторе. */
  const insertFromPalette = (kind: NodeKind, target: GraphNode | null) => {
    if (!target || !canInsertAfter(target)) {
      setError('Перетащите блок из палитры на блок схемы — новый встанет после него');
      return;
    }
    setError(null);
    selectNode(target);
    togglePanel('left', true);
    setInsertIntent((i) => ({ kind, seq: (i?.seq ?? 0) + 1 }));
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

  const dirty = !!project && draft !== savedContent;

  // Big O выделенных блоков блок-схемы: спрашиваем сервер при каждой смене выделения (только чтение, быстро).
  const complexityKey = graph?.level === 'method' && project && selection.length
    ? `${project.version}|${selection.map((n) => n.id).sort().join(',')}` : null;
  useEffect(() => {
    if (!complexityKey || !project || unsupported.has('complexity')) return;
    let alive = true;
    api.complexity(project.id, project.version, selection.map((n) => n.id))
      .then((r) => alive && setSelComplexity({ key: complexityKey, value: r.current }))
      .catch((e) => {
        if (isUnsupported(e)) markUnsupported('complexity');
        else if (alive) setSelComplexity({ key: complexityKey, value: null });
      });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- ключ уже содержит версию и выделение
  }, [complexityKey, unsupported]);
  const complexity = !complexityKey || unsupported.has('complexity') ? undefined
    : selComplexity?.key === complexityKey ? selComplexity.value : 'pending';
  const findingsCount = diagnostics?.findings.length ?? 0;

  // Горячие клавиши как в IDE: Shift+F10 — запуск, Esc — закрыть отчёт проверки.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && showVerify) setShowVerify(false);
      if (e.key === 'F10' && e.shiftKey && project && !running) {
        e.preventDefault();
        runProgram();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  /** Граница холст/редактор: тянем мышью, доля 25–80%. */
  const startSplit = (e: React.PointerEvent<HTMLDivElement>) => {
    const box = centerRef.current?.getBoundingClientRect();
    if (!box) return;
    const target = e.currentTarget;
    target.setPointerCapture(e.pointerId);
    target.classList.add('active');
    const move = (ev: PointerEvent) => setSplit(Math.min(0.8, Math.max(0.25, (ev.clientY - box.top) / box.height)));
    const up = () => {
      target.classList.remove('active');
      target.removeEventListener('pointermove', move);
      target.removeEventListener('pointerup', up);
    };
    target.addEventListener('pointermove', move);
    target.addEventListener('pointerup', up);
  };

  // Колонка полосы — ровно её ширина (--stripe-w), иначе полоса залезает под соседнюю панель.
  const columns = ['var(--stripe-w)', panels.left ? 'clamp(220px, 18vw, 280px)' : null, 'minmax(0, 1fr)', panels.right ? 'clamp(300px, 25vw, 380px)' : null].filter(Boolean).join(' ');
  const areaRow = (bottom: boolean) =>
    ['lstripe', panels.left ? (bottom ? 'bottom' : 'left') : null, bottom ? 'bottom' : 'center', panels.right ? (bottom ? 'bottom' : 'right') : null]
      .filter(Boolean).join(' ');
  /** Свёрнутая правая панель: кнопка «показать» в правом углу шапки видимого центрального острова. */
  const showRightButton = !panels.right && project && (
    <button className="ghost icon-text show-right" onClick={() => togglePanel('right', true)}
      title="Показать панель: проблемы, метрики, разбиение" aria-label="Показать панель проблем">
      <PanelRightOpen size={16} />
      {findingsCount > 0 && <span className="tab-count">{findingsCount}</span>}
    </button>
  );
  const workspaceStyle: CSSProperties = {
    gridTemplateColumns: columns,
    gridTemplateRows: panels.bottom ? 'minmax(0, 1fr) clamp(170px, 26vh, 260px)' : 'minmax(0, 1fr)',
    gridTemplateAreas: panels.bottom ? `"${areaRow(false)}" "${areaRow(true)}"` : `"${areaRow(false)}"`,
  };

  return (
    <div
      className={`app ${dragging ? 'dragging' : ''}`}
      onDragOver={(e) => { if (e.dataTransfer.types.includes('Files')) { e.preventDefault(); setDragging(true); } }}
      onDragLeave={(e) => { if (e.currentTarget === e.target) setDragging(false); }}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        const files = Array.from(e.dataTransfer.files).filter((f) => f.name.endsWith('.java'));
        if (files.length) {
          const dt = new DataTransfer();
          files.forEach((f) => dt.items.add(f));
          importFiles(dt.files);
        }
      }}
    >
      <input ref={fileInput} type="file" accept=".java" multiple hidden
        onChange={(e) => { importFiles(e.target.files); e.target.value = ''; }} />

      <header className="toolbar">
        <div className="app-logo" aria-hidden><DraftingCompass size={18} /></div>
        <span className="brand-name">Java Blueprints</span>
        <div className="project-widget">
          <FileCode2 size={15} className="icon-java" />
          <span className="project-name" title={project?.name ?? undefined}>{project ? project.name ?? activeFile : 'Нет проекта'}</span>
          {project && <span className="muted" title="Версия проекта: каждая правка — новая версия, v1 — эталон поведения">v{project.version}</span>}
        </div>
        <div className="toolbar-spacer" />
        <div className="segmented" role="group" aria-label="Раскладка">
          <button aria-pressed={view === 'graph'} onClick={() => setView('graph')} disabled={!project} title="Только граф — для показа">Граф</button>
          <button aria-pressed={view === 'split'} onClick={() => setView('split')} disabled={!project} title="Граф и код">Граф + код</button>
          <button aria-pressed={view === 'code'} onClick={() => setView('code')} disabled={!project} title="Только код">Код</button>
        </div>
        <div className="toolbar-spacer" />
        <div className="run-widget">
          <button disabled={!project || running} onClick={runProgram} title="Запустить программу (Shift+F10)">
            <Play size={15} className="run-icon" />{running ? 'Запуск…' : 'Запуск'}
          </button>
          <button className="primary" disabled={!project || verifying} onClick={runVerify}
            title="Дифф-тесты: версия 1 против текущей на одних входах, плюс замер времени">
            <ShieldCheck size={15} />{verifying ? 'Проверяем…' : 'Проверить поведение'}
          </button>
        </div>
      </header>

      <div className="workspace" style={workspaceStyle}>
        <nav className="stripe" style={{ gridArea: 'lstripe' }} aria-label="Панели слева">
          <StripeButton icon={<FileCode2 size={18} />} label="Проект и выделенный блок" active={panels.left} onClick={() => togglePanel('left')} />
          <StripeButton icon={<FolderOpen size={18} />} label="Открыть" title="Открыть .java-файлы (можно перетащить в окно)" onClick={() => fileInput.current?.click()} />
          <StripeButton icon={exporting ? <LoaderCircle size={18} className="spin" /> : <Download size={18} />} label="Экспорт с тестами"
            title={exporting ? 'Готовим архив…' : 'Экспорт с тестами: zip — текущий код, JUnit-тесты против версии 1 (golden master) и отчёт проверки; запуск — mvn test'}
            disabled={!project || exporting} onClick={exportProject} />
          <div className="stripe-spacer" />
          <StripeButton icon={<Terminal size={18} />} label="Консоль" active={panels.bottom && bottomTab === 'console'} onClick={() => openBottom('console')} />
          <StripeButton icon={<FlaskConical size={18} />} label="Результат тестов" active={panels.bottom && bottomTab === 'tests'} onClick={() => openBottom('tests')} />
          <div className="stripe-sep" />
          <StripeButton icon={theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />} label="Сменить тему"
            title={theme === 'dark' ? 'Светлая тема' : 'Тёмная тема'} onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')} />
        </nav>

        {panels.left && (
          <aside className="island sidebar" style={{ gridArea: 'left' }}>
            <div className="island-head">
              <span className="island-title">Проводник</span>
              <div className="island-actions">
                <button className="ghost icon" onClick={() => togglePanel('left', false)} title="Свернуть панель" aria-label="Свернуть проводник"><ChevronsLeft size={16} /></button>
              </div>
            </div>
            <div className="island-body pad explorer-body">
              {!project && <div className="muted">Нет открытых файлов</div>}
              {project && (
                <Explorer
                  files={project.files}
                  activeFile={activeFile}
                  onOpenFile={(path) => { const f = project.files.find((x) => x.path === path); if (f) { setActiveFile(f.path); setDraft(f.content); } }}
                  overview={overview}
                  findings={diagnostics?.findings ?? []}
                  selectedId={selected?.id ?? null}
                  onSelectMember={selectMember}
                  onOpenMethod={openMethod}
                />
              )}
            </div>
            {selected && graph && (
              <div className="inspector-dock">
                <NodeInspector selection={selection} graph={graph} onOp={applyGraphOp} complexity={complexity} insertIntent={insertIntent} onIntentHandled={() => setInsertIntent(null)} />
                {graph.level === 'method' && <div className="hint">Shift/Ctrl+клик — выделить несколько блоков подряд</div>}
              </div>
            )}
          </aside>
        )}

        <div className="center" ref={centerRef} style={{ gridArea: 'center' }}>
          <main className="island" style={{ flex: !project || view === 'graph' ? '1 1 0' : `${split} 1 0` }} hidden={!!project && view === 'code'}>
            <div className="island-head">
              <div className="tabs" role="tablist">
                <button role="tab" className={graph?.level !== 'method' ? 'active' : ''} aria-selected={graph?.level !== 'method'}
                  disabled={!project} onClick={openOverview}>
                  <LayoutGrid size={15} />Обзор
                </button>
                {openedMethod && (
                  <button role="tab" className="active" aria-selected title={openedMethod.code ?? undefined}>
                    <Workflow size={15} />{openedMethod.label}()
                    <span className="tab-close" role="button" aria-label="Закрыть блок-схему"
                      onClick={(e) => { e.stopPropagation(); openOverview(); }}><X size={13} /></span>
                  </button>
                )}
              </div>
              {overview && diagnostics && (
          <div className="chips head-chips" aria-label="Сводка проекта">
            <span className="chip-metric" title="Типы: классы, интерфейсы, enum, record"><Box size={13} />Типы <b>{overview.nodes.filter((n) => ['class', 'interface', 'enum', 'record'].includes(n.kind)).length}</b></span>
            <span className="chip-metric" title="Методы и конструкторы"><SquareFunction size={13} />Методы <b>{overview.nodes.filter((n) => n.kind === 'method' || n.kind === 'constructor').length}</b></span>
            <span className={`chip-metric ${diagnostics.findings.some((f) => f.severity === 'high') ? 'err' : ''}`} title="Проблем высокой важности">
              <CircleAlert size={13} />Критичных <b>{diagnostics.findings.filter((f) => f.severity === 'high').length}</b>
            </span>
            <span className={`chip-metric ${diagnostics.metrics.some((m) => /n\^?[2-9²³]/.test(m.complexity ?? '')) ? 'warn' : ''}`} title="Методы со сложностью выше линейной">
              <TriangleAlert size={13} />Выше O(n) <b>{diagnostics.metrics.filter((m) => /n\^?[2-9²³]|2\^n|n!/.test(m.complexity ?? '')).length}</b>
            </span>
          </div>
        )}
              {view !== 'code' && showRightButton}
            </div>
            <div className={`canvas ${graph?.level === 'method' ? 'with-palette' : ''}`}>
              {graph?.level === 'method' && (
                <BlockPalette canPick={selection.length === 1 && canInsertAfter(selection[0])}
                  onPick={(kind) => insertFromPalette(kind, selection.length === 1 ? selection[0] : null)} />
              )}
              {graph ? (
                <GraphCanvas
                  graph={graph}
                  selectedIds={selection.map((n) => n.id)}
                  onSelect={selectNode}
                  onOpenMethod={openMethod}
                  focus={focus}
                  findings={nodeFindings}
                  theme={theme}
                  onDropKind={graph.level === 'method' ? insertFromPalette : undefined}
                  canDropOn={canInsertAfter}
                />
              ) : (
                <div className="welcome">
                  <div className="welcome-card">
                    <h2>Откройте Java-монолит</h2>
                    <p>Код превратится в граф: классы, методы и блок-схемы. Найденные проблемы можно исправить, а потом доказать тестами, что поведение не изменилось.</p>
                    <div className="welcome-actions">
                      <button className="primary" onClick={() => fileInput.current?.click()}><Upload size={16} />Открыть .java</button>
                      <span className="muted">или перетащите файлы в окно</span>
                    </div>
                    <ol>
                      <li><span><strong>Обзор</strong> — классы и методы; двойной клик открывает блок-схему метода.</span></li>
                      <li><span><strong>Проблемы</strong> — справа: сложность O(n²), пустые catch, магические числа; часть исправляется автоматически.</span></li>
                      <li><span><strong>Проверить поведение</strong> — версия 1 и текущая на одних входах, плюс замер скорости.</span></li>
                    </ol>
                  </div>
                </div>
              )}
            </div>
          </main>
          {/* Без проекта редактор пуст — приветствие занимает всю колонку. */}
          <div className="splitter" role="separator" aria-orientation="horizontal" aria-label="Граница графа и кода" onPointerDown={startSplit} hidden={!project || view !== 'split'} />
          <section className="island" style={{ flex: view === 'code' ? '1 1 0' : `${1 - split} 1 0` }} hidden={!project || view === 'graph'}>
            <div className="island-head">
              {activeFile ? (
                <span className="editor-tab" title={activeFile}>
                  <FileCode2 size={14} />{activeFile}
                  {dirty && <span className="dirty-dot" title="Есть непримененные правки" />}
                </span>
              ) : <span className="island-title muted">Код</span>}
              <div className="island-actions">
                {dirty && <span className="head-hint">Ctrl+S — применить</span>}
                <button className={dirty ? 'primary' : ''} disabled={!project || !dirty} onClick={applySource}
                  title="Перестроить граф и диагностику по коду из редактора (Ctrl+S)">Применить код</button>
                {view === 'code' && showRightButton}
              </div>
            </div>
            <div className="code">
              <CodeEditor value={draft} highlight={highlight} onChange={setDraft} onCursorLine={selectLine}
                onSave={() => { if (project) applySource(); }} theme={theme} />
            </div>
          </section>
        </div>

        {panels.right && (
          <aside className="island analysis-panel" style={{ gridArea: 'right' }}>
            <div className="island-head">
              <div className="tabs" role="tablist">
                <button role="tab" data-tab="problems" className={rightTab === 'problems' ? 'active' : ''} onClick={() => setRightTab('problems')}>
                  Проблемы {diagnostics && <span className="tab-count problems-count">{findingsCount}</span>}
                </button>
                <button role="tab" className={rightTab === 'metrics' ? 'active' : ''} onClick={() => setRightTab('metrics')}>Метрики</button>
                <button role="tab" className={rightTab === 'decompose' ? 'active' : ''} onClick={() => setRightTab('decompose')}>Разбиение</button>
              </div>
              <div className="island-actions">
                <button className="ghost icon" onClick={() => togglePanel('right', false)} title="Свернуть панель — граф и код станут шире" aria-label="Свернуть панель проблем"><ChevronsRight size={16} /></button>
              </div>
            </div>
            <div className="island-body pad">
              {rightTab === 'problems' && (
                <AnalysisPanel
                  diagnostics={diagnostics}
                  onSelect={selectFinding}
                  onFix={applyFix}
                  applied={applied}
                  onVerify={runVerify}
                  onUndo={undoFix}
                  onDismissApplied={() => setApplied(null)}
                  verifying={verifying}
                  onAdvice={unsupported.has('advice') ? undefined : askAdvice}
                />
              )}
              {rightTab === 'metrics' && <MetricsPanel diagnostics={diagnostics} />}
              {rightTab === 'decompose' && (project ? (
                <DecomposePanel
                  result={decomp?.result ?? null}
                  stale={!!decomp && decomp.version !== project.version}
                  busy={decomposing}
                  overview={overview}
                  activeClass={activeClass}
                  onPropose={proposeDecomposition}
                  onShowClass={(cls, nodes) => showProposedClass(cls.className, nodes)}
                  onShowMember={showMember}
                  onApply={unsupported.has('decomposeApply') ? undefined : applyDecomposition}
                  applying={applyingDecomp}
                  applied={applied?.kind === 'decompose' ? applied : null}
                  onVerify={runVerify}
                  onUndo={undoFix}
                  onDismissApplied={() => setApplied(null)}
                  verifying={verifying}
                />
              ) : <div className="empty-state">Откройте проект, чтобы предложить разбиение</div>)}
            </div>
          </aside>
        )}


        {panels.bottom && (
          <section className="island output" style={{ gridArea: 'bottom' }}>
            <BottomPanel run={run} verify={verify} onOpenReport={() => setShowVerify(true)}
              stdin={stdin} onStdin={setStdin} onRun={runProgram} running={running} canRun={!!project}
              tab={bottomTab} onTab={setBottomTab} onHide={() => togglePanel('bottom', false)} />
          </section>
        )}
      </div>

      <footer className="statusbar">
        <div className="crumbs">
          {activeFile ? <span>{activeFile}</span> : <span>Нет проекта</span>}
          {ownerType && <><ChevronRight size={13} /><span>{ownerType.label}</span></>}
          {openedMethod && <><ChevronRight size={13} /><span>{openedMethod.label}()</span></>}
          {selected && selected.id !== openedMethod?.id && <><ChevronRight size={13} /><span>{selected.label}{selection.length > 1 ? ` и ещё ${selection.length - 1}` : ''}</span></>}
        </div>
        <div className="toolbar-spacer" />
        {dirty && <span className="status-item warn">Правки не применены</span>}
        {exported && (
          <span className={`status-item export-note ${exported.preserved === false ? 'fail' : ''}`} title={exported.fileName}>
            <Download size={13} />{exported.fileName}{exported.summary && `, ${exported.summary}`}
          </span>
        )}
        {verify && (
          <button className={`status-item ${verify.behaviorPreserved ? 'ok' : 'fail'}`} onClick={() => setShowVerify(true)} title="Открыть отчёт проверки">
            {verify.behaviorPreserved ? <CircleCheck size={13} /> : <CircleX size={13} />}
            {verify.behaviorPreserved ? 'Поведение совпадает' : 'Поведение изменилось'} {verify.passed}/{verify.total}
          </button>
        )}
        {diagnostics && (
          <button className="status-item" onClick={() => openRight('problems')} title="Проблемы">
            <CircleAlert size={13} />{findingsCount}
          </button>
        )}
        {project && <span className="status-item">Java · v{project.version}</span>}
      </footer>

      {showVerify && verify && (
        <div className="dialog-backdrop" onClick={(e) => { if (e.target === e.currentTarget) setShowVerify(false); }}>
          <VerifyScreen report={verify} running={verifying} onRerun={runVerify} onClose={() => setShowVerify(false)}
            onExport={exportProject} exporting={exporting} />
        </div>
      )}

      {error && (
        <div className="notifications" role="alert">
          <div className="notification">
            <CircleAlert size={18} />
            <span className="error">{error}</span>
            <button className="ghost icon" onClick={() => setError(null)} aria-label="Закрыть уведомление"><X size={15} /></button>
          </div>
        </div>
      )}
    </div>
  );
}
