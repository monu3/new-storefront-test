/**
 * Normalised API error.
 *
 * The backend returns RFC 7807 `ProblemDetail` with a `code` property on every handled error.
 * Carrying `code` through means a component can branch on `INVALID_CATEGORY` rather than
 * string-matching a human-readable message that will change when the wording is improved.
 */
export interface ApiError extends Error {
  status: number;
  /** Machine-readable code, e.g. `INVALID_CATEGORY`, `UNKNOWN_SHOP_TYPE`. */
  code?: string;
  title?: string;
  detail?: string;
  traceId?: string;
  /** Field-level validation problems, when the backend supplies them. */
  fieldErrors?: Record<string, string>;
  isNetworkError: boolean;
}

interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  traceId?: string;
  errors?: Record<string, string>;
}

export class HttpError extends Error implements ApiError {
  readonly status: number;
  readonly code?: string;
  readonly title?: string;
  readonly detail?: string;
  readonly traceId?: string;
  readonly fieldErrors?: Record<string, string>;
  readonly isNetworkError = false;

  constructor(status: number, problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${status}`);
    this.name = 'HttpError';
    this.status = status;
    this.code = problem.code;
    this.title = problem.title;
    this.detail = problem.detail;
    this.traceId = problem.traceId;
    this.fieldErrors = problem.errors;
  }
}

export class NetworkError extends Error implements ApiError {
  readonly status = 0;
  readonly isNetworkError = true;

  constructor(cause?: unknown) {
    super(
      'Could not reach the API. Check that the backend is running on port 8080 and that ' +
        'CORS allows this origin.',
    );
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

/** True when the session is missing or expired and the user must log in again. */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof HttpError && error.status === 401;
}

export function isForbidden(error: unknown): boolean {
  return error instanceof HttpError && error.status === 403;
}

export function errorCode(error: unknown): string | undefined {
  return error instanceof HttpError ? error.code : undefined;
}
