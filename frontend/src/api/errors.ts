import type { ApiError } from './types';

/** Ошибка API с телом из контракта (error, message, problems). Отдельный модуль — его используют и client, и моки. */
export class ApiRequestError extends Error {
  // Поля явно, без parameter properties: модуль импортируют и unit-тесты под node --test (strip-only TS).
  readonly status: number;
  readonly body: ApiError;
  constructor(status: number, body: ApiError) {
    super(body.message);
    this.status = status;
    this.body = body;
  }
}

/**
 * Эндпоинта нет на этом сервере (PR роли 4 ещё не слит или старая сборка): Spring отвечает 404/405 своим телом,
 * а «не найден проект/находка» — наш 404 с error = not_found. 501 — эндпоинт есть, но функция не реализована.
 */
export function isUnsupported(e: unknown): boolean {
  return e instanceof ApiRequestError && (e.status === 501 || ((e.status === 404 || e.status === 405) && e.body.error !== 'not_found'));
}
