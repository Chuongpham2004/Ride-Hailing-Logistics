import { HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { config } from '@/shared/config';

/** Fake gateway for tests; each test adds the handlers it needs with server.use(). */
export const server = setupServer();

export const apiUrl = (path: string) => config.apiUrl + path;

/** A success envelope as the services send it. */
export function ok<T>(data: T, status = 200) {
  return HttpResponse.json({ code: 'SUCCESS', message: 'Request completed successfully', data }, { status });
}

/** An error envelope as the services send it. */
export function fail(status: number, code: string, details?: { field: string; reason: string }[]) {
  return HttpResponse.json(
    { code, message: `server says ${code}`, correlationId: 'test-correlation', details },
    { status },
  );
}
