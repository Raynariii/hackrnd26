import type { ApiError, ChangeResult, Diagnostics, Graph, GraphOpRequest, ProjectState, RunResult, SourceFile, VerifyReport, VerifyRequest } from './types';
import { ApiRequestError } from './errors';

export { ApiRequestError };

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
  /** Операция над графом → изменение AST → новый код; граф после неё перечитывается (id пересчитаны). */
  graphOp: (id: string, body: GraphOpRequest) => request<ProjectState>('POST', `/projects/${id}/graph/ops`, body),
  /** Авто-исправление находки (FixEngine роли 2). Находка должна быть из текущей версии. */
  applyFix: (id: string, findingId: string, version: number) =>
    request<ChangeResult>('POST', `/projects/${id}/fixes/${encodeURIComponent(findingId)}`, { version }),
  getDiagnostics: (id: string) => request<Diagnostics>('GET', `/projects/${id}/diagnostics`),
  run: (id: string, stdin = '') => request<RunResult>('POST', `/projects/${id}/run`, { stdin }),
  verify: (id: string, body: VerifyRequest = {}) => request<VerifyReport>('POST', `/projects/${id}/verify`, body),
};

export type Api = typeof realApi;

/**
 * VITE_USE_MOCKS=true — фронт работает на фикстурах без бэкенда (npm run dev:mock).
 * Моки грузятся динамически: в обычной сборке их код и снимки ядра (~сотни КБ) не скачиваются.
 */
export const api: Api =
  import.meta.env.VITE_USE_MOCKS === 'true' ? (await import('../mocks/mockApi')).mockApi : realApi;
