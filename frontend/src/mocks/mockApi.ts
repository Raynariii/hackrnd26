import type { ChangeResult, Diagnostics, Graph, GraphOpRequest, ProjectState, RunResult, SourceFile, VerifyReport, VerifyRequest } from '../api/types';
import { mockDiagnostics, mockMethodFlow, mockOverview, mockProject, mockRun, mockVerify, mockVerifyFail, mockVerifyNoCompile } from './fixtures';
import { MAIN_FIX_REJECTION, opsDemo, ordersFixesDemo, realDemos, type RealDemo, type Snapshot } from './realDemos';
import { ApiRequestError } from '../api/errors';

/** ?demo=flow | ?demo=orders — настоящие графы ядра вместо ручного мока (см. realDemos.ts). */
const params = new URLSearchParams(globalThis.location?.search ?? '');
const demoName = params.get('demo') ?? '';
/** Текущее состояние настоящего демо; авто-исправление заменяет его снимком «после». */
let demo: RealDemo | undefined = realDemos[demoName];
const demoOriginal = demo;

const delay = <T,>(value: T) => new Promise<T>((resolve) => setTimeout(() => resolve(structuredClone(value)), 150));

const opsMode = params.get('demo') === 'main';
/** Текущий снимок ?demo=main: меняется только записанными операциями. */
let snapshot: Snapshot = opsDemo;

let project: ProjectState = demo
  ? { ...mockProject, name: demo.file, files: [{ path: demo.file, content: demo.source }] }
  : opsMode
    ? { ...mockProject, name: opsDemo.file, files: [{ path: opsDemo.file, content: opsDemo.source }] }
    : mockProject;

const unprocessable = (message: string) => Promise.reject(new ApiRequestError(422, { error: 'unsupported_op', message }));

export const mockApi = {
  createProject: (files: SourceFile[], name?: string): Promise<ProjectState> => {
    project = { ...mockProject, name: name ?? 'demo', files };
    return delay(project);
  },
  getProject: (_id: string): Promise<ProjectState> => delay(project),
  updateSource: (_id: string, version: number, files: SourceFile[]): Promise<ProjectState> => {
    project = { ...project, version: version + 1, files };
    return delay(project);
  },
  getGraph: (_id: string, level: 'overview' | 'method' = 'overview', method?: string): Promise<Graph> => {
    if (opsMode) {
      if (level === 'overview') return delay(snapshot.overview);
      return method === snapshot.flow.scope ? delay(snapshot.flow) : unprocessable('В демо есть блок-схема только processOrders');
    }
    if (!demo) return delay(level === 'method' ? mockMethodFlow : mockOverview);
    if (level === 'overview') return delay(demo.overview);
    const flow = method ? demo.methods[method] : undefined;
    return flow ? delay(flow) : Promise.reject(new Error(`В демо нет блок-схемы для ${method}`));
  },
  graphOp: (_id: string, body: GraphOpRequest): Promise<ProjectState> => {
    if (!opsMode) return unprocessable('Правка на графе в режиме моков — только в ?demo=main');
    if (body.version !== project.version) return Promise.reject(new ApiRequestError(409, { error: 'version_conflict', message: 'Проект изменился' }));
    const ids = (x: { nodeId?: string | null; nodeIds?: string[] | null }) => [...(x.nodeIds ?? (x.nodeId ? [x.nodeId] : []))].sort().join(',');
    const same = (a: string | null | undefined, b: string | null | undefined) => (a ?? '').trim() === (b ?? '').trim();
    const rec = snapshot === opsDemo
      ? Object.values(opsDemo.ops).find(
          (r) =>
            r.request.op === body.op &&
            ids(r.request) === ids(body) &&
            same(r.request.code, body.code) &&
            same(r.request.targetId, body.targetId) &&
            same(r.request.name, body.name),
        )
      : undefined;
    if (!rec) {
      const known = Object.values(opsDemo.ops).map((r) => `${r.request.op}${r.request.code ? ` «${r.request.code}»` : ''}${r.request.name ? ` ${r.request.name}` : ''}`).join(', ');
      return unprocessable(`В демо записаны только операции из исходного состояния (обновите страницу): ${known}`);
    }
    if (rec.rejected || !rec.after) return unprocessable(rec.rejected ?? 'Операция отклонена');
    snapshot = rec.after;
    project = { ...project, version: project.version + 1, files: [{ path: opsDemo.file, content: rec.after.source }] };
    return delay(project);
  },
  applyFix: (_id: string, findingId: string, version: number): Promise<ChangeResult> => {
    if (version !== project.version) return Promise.reject(new ApiRequestError(409, { error: 'version_conflict', message: 'Проект изменился' }));
    if (!demo && !opsMode) return unprocessable(MAIN_FIX_REJECTION);
    const rec = demoName === 'orders' && demo === demoOriginal ? ordersFixesDemo[findingId] : undefined;
    if (!rec) return unprocessable('В демо записаны исправления только из исходного состояния ?demo=orders (обновите страницу)');
    if ('rejected' in rec) return unprocessable(rec.rejected);
    demo = { ...demoOriginal!, ...rec.after };
    project = { ...project, version: project.version + 1, files: [{ path: demo.file, content: demo.source }] };
    // Как в api (PR #21): описание от FixEngine и отчёт quality gate. Gate в моке — итог без кейсов:
    // сервер прогоняет предыдущую и новую версию на одних входах, здесь его не повторить.
    const gate: VerifyReport = { baselineVersion: version, currentVersion: project.version, compiled: true, behaviorPreserved: true, passed: 6, total: 6, cases: [] };
    return delay({ state: project, diff: rec.diff, description: rec.description, verify: gate });
  },
  getDiagnostics: (_id: string): Promise<Diagnostics> =>
    delay(
      opsMode
        ? { version: project.version, ...snapshot.diagnostics }
        : demo
          ? { version: project.version, ...demo.diagnostics }
          : mockDiagnostics,
    ),
  run: (_id: string, _stdin = ''): Promise<RunResult> => delay(mockRun),
  verify: (_id: string, body: VerifyRequest = {}): Promise<VerifyReport> => {
    const mode = params.get('verify');
    const report = mode === 'fail' ? mockVerifyFail : mode === 'nocompile' ? mockVerifyNoCompile : mockVerify;
    const withVersion = { ...report, currentVersion: project.version };
    return delay(body.benchmark ? withVersion : { ...withVersion, benchmark: undefined });
  },
};
