import type { Diagnostics, Graph, ProjectState, RunResult, SourceFile, VerifyReport, VerifyRequest } from '../api/types';
import { mockDiagnostics, mockMethodFlow, mockOverview, mockProject, mockRun, mockVerify, mockVerifyFail } from './fixtures';
import { realDemos } from './realDemos';

/** ?demo=flow | ?demo=orders — настоящие графы ядра вместо ручного мока (см. realDemos.ts). */
const params = new URLSearchParams(globalThis.location?.search ?? '');
const demo = realDemos[new URLSearchParams(globalThis.location?.search ?? '').get('demo') ?? ''];

const delay = <T,>(value: T) => new Promise<T>((resolve) => setTimeout(() => resolve(structuredClone(value)), 150));

let project: ProjectState = demo
  ? { ...mockProject, name: demo.file, files: [{ path: demo.file, content: demo.source }] }
  : mockProject;

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
    if (!demo) return delay(level === 'method' ? mockMethodFlow : mockOverview);
    if (level === 'overview') return delay(demo.overview);
    const flow = method ? demo.methods[method] : undefined;
    return flow ? delay(flow) : Promise.reject(new Error(`В демо нет блок-схемы для ${method}`));
  },
  getDiagnostics: (_id: string): Promise<Diagnostics> =>
    delay(demo ? { version: project.version, ...demo.diagnostics } : mockDiagnostics),
  run: (_id: string, _stdin = ''): Promise<RunResult> => delay(mockRun),
  verify: (_id: string, body: VerifyRequest = {}): Promise<VerifyReport> => {
    const report = params.get('verify') === 'fail' ? mockVerifyFail : mockVerify;
    const withVersion = { ...report, currentVersion: project.version };
    return delay(body.benchmark ? withVersion : { ...withVersion, benchmark: undefined });
  },
};
