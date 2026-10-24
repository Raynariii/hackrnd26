import type { Advice, ApiError, ChangeResult, ComplexityResult, DecomposeResult, Diagnostics, Graph, GraphOpRequest, ProjectState, RunResult, RunStreamEvent, SourceFile, VerifyReport, VerifyRequest } from './types';
import { ApiRequestError, isUnsupported } from './errors';
import { readExportHeaders, type ExportFile } from './download';
import { parseSse } from './sse';

export { ApiRequestError, isUnsupported };


async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw await failure(response);
  return (await response.json()) as T;
}

async function failure(response: Response): Promise<ApiRequestError> {
  const error = (await response.json().catch(() => ({ error: 'http_error', message: response.statusText }))) as ApiError;
  return new ApiRequestError(response.status, error);
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
  /** Предложение разбиения монолита на классы (Decomposer роли 2). */
  decompose: (id: string, version: number) =>
    request<DecomposeResult>('POST', `/projects/${id}/decompose`, { version, apply: false }),
  /** Применить разбиение (PR #55): новые классы на копии → quality gate → новая версия в `result`. До #55 — 501. */
  applyDecomposition: (id: string, version: number) =>
    request<DecomposeResult>('POST', `/projects/${id}/decompose`, { version, apply: true }),
  /** Big O выделенных блоков блок-схемы (PR #42). Только чтение; 409 — граф от другой версии. */
  complexity: (id: string, version: number, nodeIds: string[]) =>
    request<ComplexityResult>('POST', `/projects/${id}/complexity`, { version, nodeIds }),
  /** Совет LLM по находке (PR #40). null — советчик выключен на сервере (204, нет ключа). */
  advice: async (id: string, findingId: string): Promise<Advice | null> => {
    const response = await fetch(`/api/projects/${id}/advice`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ findingId }),
    });
    if (response.status === 204) return null;
    if (!response.ok) throw await failure(response);
    return (await response.json()) as Advice;
  },
  /**
   * Запуск с потоковой консолью (PR #56): события приходят по мере вывода программы.
   * Возвращает false, если сервер не умеет стримить, — тогда вызывающий делает обычный run.
   */
  runStream: async (id: string, stdin: string, onEvent: (e: RunStreamEvent) => void, signal?: AbortSignal): Promise<boolean> => {
    const response = await fetch(`/api/projects/${id}/run/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ stdin }),
      signal,
    });
    if (!response.ok) {
      const error = await failure(response);
      if (isUnsupported(error)) return false;
      throw error;
    }
    if (!response.body || !response.headers.get('Content-Type')?.includes('text/event-stream')) return false;
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      const { events, rest } = parseSse(buffer + value);
      buffer = rest;
      events.forEach(onEvent);
    }
    parseSse(`${buffer}\n\n`).events.forEach(onEvent);
    return true;
  },
  /** Zip: текущий код + JUnit golden-master тесты против версии 1 + отчёт проверки; сервер сам прогоняет verify. */
  exportProject: async (id: string): Promise<ExportFile> => {
    const response = await fetch(`/api/projects/${id}/export`);
    if (!response.ok) throw await failure(response);
    return readExportHeaders(response.headers, await response.blob(), `project-${id}.zip`);
  },
};

export type Api = typeof realApi;

/**
 * VITE_USE_MOCKS=true — фронт работает на фикстурах без бэкенда (npm run dev:mock).
 * Моки грузятся динамически: в обычной сборке их код и снимки ядра (~сотни КБ) не скачиваются.
 */
export const api: Api =
  import.meta.env.VITE_USE_MOCKS === 'true' ? (await import('../mocks/mockApi')).mockApi : realApi;
