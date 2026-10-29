import type { FieldError } from './types';

/**
 * A failed request. `code` is the server's error code (README §8.2), or NETWORK_ERROR when the
 * server could not be reached. The UI maps codes to its own wording and never shows `message`.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: FieldError[];
  readonly correlationId: string | undefined;

  constructor(
    status: number,
    code: string,
    message: string,
    details: FieldError[] = [],
    correlationId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
    this.correlationId = correlationId;
  }

  static network(cause: unknown): ApiError {
    const error = new ApiError(0, 'NETWORK_ERROR', 'The server could not be reached');
    error.cause = cause;
    return error;
  }
}
