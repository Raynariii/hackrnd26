import type { Diagnostics, Graph, ProjectState, RunResult, SourceFile, VerifyReport } from '../api/types';
import { mockDiagnostics, mockMethodFlow, mockOverview, mockProject, mockRun, mockVerify } from './fixtures';

const delay = <T,>(value: T) => new Promise<T>((resolve) => setTimeout(() => resolve(structuredClone(value)), 150));

let project = mockProject;

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
  getGraph: (_id: string, level: 'overview' | 'method' = 'overview', _method?: string): Promise<Graph> =>
    delay(level === 'method' ? mockMethodFlow : mockOverview),
  getDiagnostics: (_id: string): Promise<Diagnostics> => delay(mockDiagnostics),
  run: (_id: string, _stdin = ''): Promise<RunResult> => delay(mockRun),
  verify: (_id: string): Promise<VerifyReport> => delay(mockVerify),
};
