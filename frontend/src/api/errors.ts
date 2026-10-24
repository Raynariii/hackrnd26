import type { ApiError } from './types';

/** Ошибка API с телом из контракта (error, message, problems). Отдельный модуль — его используют и client, и моки. */
export class ApiRequestError extends Error {
  constructor(readonly status: number, readonly body: ApiError) {
    super(body.message);
  }
}
