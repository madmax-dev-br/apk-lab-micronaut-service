// Errors of the sidecar operations. `status` is the HTTP status the Micronaut runner answers with (the sidecar itself speaks no HTTP); `code` is a stable machine name.
export class HttpError extends Error {
  constructor(status, code, message, details = undefined) { super(message); this.status = status; this.code = code; this.details = details; }
}
export const badRequest = (message, details) => new HttpError(400, 'bad-request', message, details);
export const notFound = (message = 'Not found') => new HttpError(404, 'not-found', message);
