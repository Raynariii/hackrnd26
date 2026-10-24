// Типы по contracts/graph.schema.json и contracts/openapi.yaml.
// Меняются только вслед за контрактами — не наоборот.

export type NodeKind =
  | 'class' | 'interface' | 'enum' | 'record' | 'method' | 'constructor' | 'field'
  | 'start' | 'end'
  | 'variable' | 'condition' | 'loop' | 'call' | 'operation' | 'return'
  | 'switch' | 'case' | 'break' | 'continue'
  | 'try' | 'catch' | 'finally' | 'throw'
  | 'raw';

export type EdgeKind =
  | 'next' | 'true' | 'false' | 'loopBody' | 'loopBack' | 'loopExit'
  | 'case' | 'break' | 'continue' | 'exception' | 'finally' | 'call' | 'contains';

export interface SourceRange {
  file: string;
  startLine: number;
  endLine: number;
  startColumn?: number | null;
  endColumn?: number | null;
}

export interface GraphNode {
  id: string;
  astId?: string | null;
  kind: NodeKind;
  label: string;
  code?: string | null;
  range?: SourceRange | null;
  parent?: string | null;
  findings?: string[];
  meta?: Record<string, unknown>;
}

export interface GraphEdge {
  id: string;
  from: string;
  to: string;
  kind: EdgeKind;
  label?: string | null;
}

export interface Graph {
  level: 'overview' | 'method';
  scope?: string | null;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface SourceFile {
  path: string;
  content: string;
}

export interface Problem {
  file?: string | null;
  line?: number | null;
  column?: number | null;
  message: string;
}

export interface ProjectState {
  id: string;
  name?: string;
  version: number;
  files: SourceFile[];
  parseProblems?: Problem[];
}

export type Severity = 'high' | 'medium' | 'low';

export interface Finding {
  id: string;
  rule: string;
  severity: Severity;
  title: string;
  explanation: string;
  file: string;
  startLine: number;
  endLine: number;
  nodeIds?: string[];
  complexity?: { current: string; after?: string | null } | null;
  fix?: { kind: string; description?: string; preview?: string | null; autoApplicable?: boolean } | null;
  expectedEffect?: string | null;
}

export interface MethodMetrics {
  methodId: string;
  name: string;
  file: string;
  lines: number;
  cyclomatic?: number;
  maxNesting?: number;
  complexity?: string | null;
}

export interface Diagnostics {
  version: number;
  findings: Finding[];
  metrics: MethodMetrics[];
}

export interface RunResult {
  compile: { ok: boolean; problems?: Problem[]; output?: string };
  stdout?: string;
  stderr?: string;
  exitCode?: number | null;
  timeMs?: number;
  timedOut: boolean;
}

/** POST /projects/{id}/run/stream — события text/event-stream (поле data — JSON) */
export type RunStreamEvent =
  | { event: 'compile'; data: { ok: boolean; problems?: Problem[]; output?: string } }
  | { event: 'stdout' | 'stderr'; data: { text: string } }
  | { event: 'exit'; data: { exitCode: number | null; timeMs: number; timedOut: boolean } }
  | { event: 'error'; data: { message: string } };

export interface VerifyCase {
  name: string;
  input?: string;
  expected?: RunResult;
  actual?: RunResult;
  ok: boolean;
  mismatch?: string | null;
}

/** POST /projects/{id}/graph/ops — contracts/openapi.yaml GraphOpRequest. */
export interface GraphOpRequest {
  version: number;
  op: 'editNode' | 'insertAfter' | 'delete' | 'move' | 'wrapIf' | 'wrapLoop' | 'extractMethod';
  nodeId?: string;
  nodeIds?: string[];
  code?: string;
  kind?: NodeKind;
  targetId?: string;
  name?: string;
}

export interface VerifyRequest {
  /** stdin-входы; если пусто — сервер берёт сохранённые и сгенерированные. */
  inputs?: string[];
  fuzz?: boolean;
  benchmark?: boolean;
  benchmarkInputs?: { n: number; stdin: string }[];
}

export interface VerifyReport {
  baselineVersion?: number;
  currentVersion?: number;
  compiled?: boolean;
  behaviorPreserved: boolean;
  passed: number;
  total: number;
  cases: VerifyCase[];
  benchmark?: { n: number; baselineMs: number; currentMs: number }[];
}

export interface ApiError {
  error: string;
  message: string;
  problems?: Problem[];
}

/** POST /projects/{id}/fixes/{findingId} — contracts/openapi.yaml → ChangeResult */
export interface ChangeResult {
  state: ProjectState;
  diff: string;
  description?: string | null;
  /** Отчёт quality gate: предыдущая версия vs новая */
  verify?: VerifyReport | null;
}

/** POST /projects/{id}/decompose — contracts/openapi.yaml → DecomposeResult */
export interface DecomposeResult {
  proposal: { className: string; responsibility?: string; members: string[] }[];
  notes?: string[];
  result?: ChangeResult | null;
}

/** POST /projects/{id}/advice — contracts/openapi.yaml → Advice (204 — советчик выключен) */
export interface Advice {
  findingId: string;
  suggestion: string;
  model?: string | null;
}

/** POST /projects/{id}/complexity — contracts/openapi.yaml → ComplexityResult */
export interface ComplexityResult {
  version: number;
  current: string;
}
