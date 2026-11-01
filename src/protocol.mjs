export function nowIso() {
  return new Date().toISOString();
}

export function nowMs() {
  return Date.now();
}

export function createResponse(id, payload, type = 'response') {
  return { type, id, ok: true, payload };
}

export function createError(id, code, message, details = undefined, type = 'error') {
  return { type, id, ok: false, error: { code, message, details } };
}

export function createEvent(event, fields = {}) {
  return { type: 'event', event, ...fields };
}
