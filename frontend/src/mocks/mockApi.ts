import type { Diagnostics, Graph, GraphOpRequest, ProjectState, RunResult, SourceFile, VerifyReport, VerifyRequest } from '../api/types';
import { mockDiagnostics, mockMethodFlow, mockOverview, mockProject, mockRun, mockVerify, mockVerifyFail } from './fixtures';
import { opsDemo, realDemos, type Snapshot } from './realDemos';
import { ApiRequestError } from '../api/errors';

/** ?demo=flow | ?demo=orders — настоящие графы ядра вместо ручного мока (см. realDemos.ts). */
const params = new URLSearchParams(globalThis.location?.search ?? '');
const demo = realDemos[new URLSearchParams(globalThis.location?.search ?? '').get('demo') ?? ''];

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
    const rec = snapshot === opsDemo
      ? Object.values(opsDemo.ops).find(
          (r) => r.request.op === body.op && r.request.nodeId === body.nodeId && (r.request.code ?? '') === (body.code ?? '').trim(),
        )
      : undefined;
    if (!rec) {
      const known = Object.values(opsDemo.ops).map((r) => `${r.request.op} «${r.request.code ?? '—'}»`).join(', ');
      return unprocessable(`В демо записаны только операции из исходного состояния (обновите страницу): ${known}`);
    }
    snapshot = rec.after;
    project = { ...project, version: project.version + 1, files: [{ path: opsDemo.file, content: rec.after.source }] };
    return delay(project);
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
    const report = params.get('verify') === 'fail' ? mockVerifyFail : mockVerify;
    const withVersion = { ...report, currentVersion: project.version };
    return delay(body.benchmark ? withVersion : { ...withVersion, benchmark: undefined });
  },
};
