import type { ApiError, Diagnostics, Graph, ProjectState, RunResult, SourceFile, VerifyReport } from './types';
import { mockApi } from '../mocks/mockApi';

export class ApiRequestError extends Error {
  constructor(readonly status: number, readonly body: ApiError) {
    super(body.message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => ({ error: 'http_error', message: response.statusText }))) as ApiError;
    throw new ApiRequestError(response.status, error);
  }
  return (await response.json()) as T;
}

const realApi = {
  createProject: (files: SourceFile[], name?: string) =>
    request<ProjectState>('POST', '/projects', { name, files }),
  getProject: (id: string) => request<ProjectState>('GET', `/projects/${id}`),
  updateSource: (id: string, version: number, files: SourceFile[]) =>
    request<ProjectState>('PUT', `/projects/${id}/source`, { version, files }),
  getGraph: (id: string, level: 'overview' | 'method' = 'overview', method?: string) =>
    request<Graph>('GET', `/projects/${id}/graph?level=${level}${method ? `&method=${encodeURIComponent(method)}` : ''}`),
  getDiagnostics: (id: string) => request<Diagnostics>('GET', `/projects/${id}/diagnostics`),
  run: (id: string, stdin = '') => request<RunResult>('POST', `/projects/${id}/run`, { stdin }),
  verify: (id: string) => request<VerifyReport>('POST', `/projects/${id}/verify`, {}),
};

export type Api = typeof realApi;

/** VITE_USE_MOCKS=true — фронт работает на фикстурах без бэкенда (npm run dev:mock). */
export const api: Api = import.meta.env.VITE_USE_MOCKS === 'true' ? mockApi : realApi;
