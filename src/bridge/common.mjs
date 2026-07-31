export function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

export function requestKey(value) {
  if (value === undefined || value === null) return '';
  return String(value);
}

export function firstNonEmptyString(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) {
      return value;
    }
  }
  return '';
}

export function safeJson(text) {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch (error) {
    return { ok: false, error };
  }
}

export function safeJsonLineParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function extractUserMessageText(content = []) {
  return content
    .filter((item) => item?.type === 'text' && typeof item.text === 'string')
    .map((item) => item.text.trim())
    .filter(Boolean)
    .join('\n');
}

export function normalizeUserMessageContent(input, fallbackText = '') {
  const content = Array.isArray(input)
    ? input.filter((item) => item && typeof item === 'object' && !Array.isArray(item))
    : [];
  if (content.length > 0) return content;
  const text = String(fallbackText ?? '').trim();
  return text ? [{ type: 'text', text }] : [];
}

export function userMessageContentSignature(input, fallbackText = '') {
  const normalized = normalizeUserMessageContent(input, fallbackText).map((item) => {
    if (item.type === 'text') return { type: 'text', text: String(item.text ?? '').trim() };
    if (item.type === 'image') return { type: 'image', url: String(item.url ?? '').trim() };
    if (item.type === 'localImage') return { type: 'localImage', path: String(item.path ?? '').trim() };
    return item;
  });
  return JSON.stringify(normalized);
}

export function normalizeTurnStatusValue(status) {
  if (typeof status === 'string') return status.trim();
  if (status && typeof status === 'object') {
    return String(status.type ?? status.status ?? '').trim();
  }
  return '';
}

export function getPayloadItemId(payload = {}) {
  return firstNonEmptyString(payload.itemId, payload.item_id);
}

export function getTurnInputItemId({ payload = {}, requestId = '', turnId = '', sessionId = '', seq = '' } = {}) {
  return firstNonEmptyString(
    getPayloadItemId(payload),
    requestId ? `input_${requestId}` : '',
    turnId ? `stored_${turnId}_user_${seq}` : '',
    sessionId ? `stored_${sessionId}_user_${seq}` : '',
  );
}

export function backendName(backend) {
  return backend?.constructor?.name?.replace('Backend', '').toLowerCase() || 'mock';
}

export function summarizeApprovalPayload(payload = {}) {
  const availableDecisions = Array.isArray(payload?.availableDecisions)
    ? payload.availableDecisions
    : [];
  return {
    item_id: payload?.itemId ?? payload?.item_id ?? '',
    command: payload?.command ?? '',
    cwd: payload?.cwd ?? '',
    reason: payload?.reason ?? '',
    available_decisions: availableDecisions,
    permissions: payload?.permissions ?? null,
  };
}

export function summarizeClientPayload(type, payload = {}) {
  if (!payload || typeof payload !== 'object') return {};
  switch (type) {
    case 'session.content':
    case 'session.sync':
    case 'turn.send':
    case 'turn.steer':
    case 'turn.interrupt':
    case 'approval.response':
      return {
        session_id: payload.session_id ?? payload.thread_id ?? '',
        request_id: payload.request_id ?? payload.requestId ?? '',
        turn_id: payload.turn_id ?? payload.turnId ?? '',
        text_len: String(payload.text ?? '').length,
        decision: payload.decision ?? payload.result?.decision ?? '',
      };
    case 'session.start':
      return {
        title: payload.title ?? '',
        cwd: payload.cwd ?? '',
        model: payload.model ?? '',
        approvalPolicy: payload.approvalPolicy ?? '',
      };
    case 'model.list':
      return { includeHidden: payload.includeHidden ?? null };
    default:
      return {};
  }
}
