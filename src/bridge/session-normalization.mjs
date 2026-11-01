import { nowIso } from '../protocol.mjs';
import { clone, firstNonEmptyString } from './common.mjs';

const SANDBOX_POLICY_TYPES = new Set(['readOnly', 'workspaceWrite', 'dangerFullAccess', 'externalSandbox']);
const SANDBOX_MODE_TYPES = new Set(['readOnly', 'workspaceWrite', 'dangerFullAccess']);

export function mergeSessionSummary(summary, stored) {
  if (!stored) return clone(summary);
  const merged = {
    ...clone(summary),
    session_id: summary.session_id ?? stored.session_id,
    thread_id: summary.thread_id ?? stored.thread_id ?? summary.session_id,
    title: firstNonEmptyString(summary.title, stored.title),
    cwd: firstNonEmptyString(summary.cwd, stored.cwd),
    model: stored.modelOverride && firstNonEmptyString(stored.model)
      ? stored.model
      : firstNonEmptyString(summary.model, stored.model),
    preview: firstNonEmptyString(summary.preview, stored.preview),
    backend: firstNonEmptyString(summary.backend, stored.backend),
    createdAt: firstNonEmptyString(summary.createdAt, stored.createdAt),
    updatedAt: firstNonEmptyString(summary.updatedAt, stored.updatedAt),
    active: summary.active ?? stored.active ?? true,
    thread: clone(summary.thread ?? stored.thread ?? null),
  };

  if (stored.modelOverride) {
    merged.modelOverride = true;
  }

  if (summary.approvalPolicy != null && !(typeof summary.approvalPolicy === 'string' && summary.approvalPolicy.trim() === '')) {
    merged.approvalPolicy = clone(summary.approvalPolicy);
  } else if (stored.approvalPolicy != null) {
    merged.approvalPolicy = clone(stored.approvalPolicy);
  }

  if (summary.permissions != null) {
    merged.permissions = clone(summary.permissions);
  } else if (stored.permissions != null) {
    merged.permissions = clone(stored.permissions);
  }

  if (summary.sandbox != null) {
    merged.sandbox = stored.sandboxOverride && stored.sandbox != null
      ? clone(stored.sandbox)
      : clone(summary.sandbox);
  } else if (stored.sandbox != null) {
    merged.sandbox = clone(stored.sandbox);
  }
  if (stored.sandboxOverride) {
    merged.sandboxOverride = true;
  }

  if (Number.isFinite(summary.contextWindow) && summary.contextWindow > 0) {
    merged.contextWindow = summary.contextWindow;
  } else if (Number.isFinite(stored.contextWindow) && stored.contextWindow > 0) {
    merged.contextWindow = stored.contextWindow;
  }

  if (summary.lastTokenUsage != null) {
    merged.lastTokenUsage = clone(summary.lastTokenUsage);
  } else if (stored.lastTokenUsage != null) {
    merged.lastTokenUsage = clone(stored.lastTokenUsage);
  }

  if (summary.totalTokenUsage != null) {
    merged.totalTokenUsage = clone(summary.totalTokenUsage);
  } else if (stored.totalTokenUsage != null) {
    merged.totalTokenUsage = clone(stored.totalTokenUsage);
  }

  if (stored.reasoningEffortOverride && firstNonEmptyString(stored.reasoningEffort)) {
    merged.reasoningEffort = clone(stored.reasoningEffort);
    merged.reasoningEffortOverride = true;
  } else if (summary.reasoningEffort != null && !(typeof summary.reasoningEffort === 'string' && summary.reasoningEffort.trim() === '')) {
    merged.reasoningEffort = clone(summary.reasoningEffort);
  } else if (stored.reasoningEffort != null) {
    merged.reasoningEffort = clone(stored.reasoningEffort);
  }

  return merged;
}

function canonicalizeSandboxType(value) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  const candidate = trimmed.includes('-')
    ? trimmed.split('-').map((part, index) => (index === 0 ? part : `${part.slice(0, 1).toUpperCase()}${part.slice(1)}`)).join('')
    : trimmed;
  return SANDBOX_POLICY_TYPES.has(candidate) ? candidate : null;
}

export function toSandboxMode(value) {
  const type = typeof value === 'string'
    ? canonicalizeSandboxType(value)
    : canonicalizeSandboxType(value?.type);
  if (!type || !SANDBOX_MODE_TYPES.has(type)) return null;
  return type.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);
}

export function normalizeSandboxPolicy(value) {
  if (value == null) return null;

  if (typeof value === 'string') {
    switch (canonicalizeSandboxType(value)) {
      case 'dangerFullAccess':
        return { type: 'dangerFullAccess' };
      case 'readOnly':
        return { type: 'readOnly', networkAccess: false };
      case 'workspaceWrite':
        return {
          type: 'workspaceWrite',
          writableRoots: [],
          networkAccess: false,
          excludeTmpdirEnvVar: false,
          excludeSlashTmp: false,
        };
      default:
        return null;
    }
  }

  if (typeof value !== 'object') return null;
  const type = canonicalizeSandboxType(value.type);
  if (!type) return null;

  const next = clone(value);
  next.type = type;

  switch (type) {
    case 'dangerFullAccess':
      return { type };
    case 'readOnly':
      return {
        ...next,
        networkAccess: Boolean(next.networkAccess),
      };
    case 'workspaceWrite':
      return {
        ...next,
        writableRoots: Array.isArray(next.writableRoots) ? clone(next.writableRoots) : [],
        networkAccess: Boolean(next.networkAccess),
        excludeTmpdirEnvVar: Boolean(next.excludeTmpdirEnvVar),
        excludeSlashTmp: Boolean(next.excludeSlashTmp),
      };
    case 'externalSandbox':
      return next.networkAccess != null ? next : null;
    default:
      return null;
  }
}

export function normalizePermissionSelection(value) {
  if (!value || typeof value !== 'object') return null;
  const type = typeof value.type === 'string' ? value.type.trim() : '';
  if (type && type !== 'profile') return null;
  const id = typeof value.id === 'string' ? value.id.trim() : '';
  if (!id) return null;
  const modifications = Array.isArray(value.modifications)
    ? value.modifications.filter(Boolean)
    : [];
  return {
    type: 'profile',
    id,
    ...(modifications.length > 0 ? { modifications: clone(modifications) } : {}),
  };
}

export function normalizeThreadSummary(thread, backend = 'mock') {
  if (!thread) return null;
  const envelope = thread ?? {};
  const raw = envelope.thread ?? envelope;
  const id = raw.id ?? raw.threadId ?? raw.sessionId;
  const approvalPolicy = envelope.approvalPolicy ?? raw.approvalPolicy;
  const permissions = normalizePermissionSelection(envelope.activePermissionProfile ?? raw.activePermissionProfile);
  const sandbox = normalizeSandboxPolicy(envelope.sandbox ?? raw.sandbox ?? raw.sandboxPolicy);
  const tokenUsage = envelope.tokenUsage ?? raw.tokenUsage ?? null;
  const reasoningEffort = envelope.reasoningEffort ?? raw.reasoningEffort ?? null;
  const historyUpdatedAt = inferThreadHistoryUpdatedAt(raw);
  return {
    session_id: id,
    thread_id: id,
    title: raw.name ?? raw.title ?? raw.preview ?? '',
    cwd: envelope.cwd ?? raw.cwd ?? raw.path ?? '',
    model: envelope.model ?? raw.model ?? '',
    preview: raw.preview ?? '',
    active: raw.status ? String(raw.status) === 'active' || String(raw.status) === 'running' : true,
    backend,
    createdAt: raw.createdAt ? new Date(raw.createdAt * 1000).toISOString() : nowIso(),
    updatedAt: historyUpdatedAt ?? (raw.updatedAt ? new Date(raw.updatedAt * 1000).toISOString() : nowIso()),
    ...(approvalPolicy != null ? { approvalPolicy } : {}),
    ...(permissions != null ? { permissions } : {}),
    ...(sandbox != null ? { sandbox } : {}),
    ...(tokenUsage?.modelContextWindow != null ? { contextWindow: tokenUsage.modelContextWindow } : {}),
    ...(tokenUsage?.last ? { lastTokenUsage: clone(tokenUsage.last) } : {}),
    ...(tokenUsage?.total ? { totalTokenUsage: clone(tokenUsage.total) } : {}),
    ...(firstNonEmptyString(reasoningEffort ?? '') ? { reasoningEffort } : {}),
    thread: clone(raw),
  };
}

export function toSessionListItem(session) {
  if (!session) return null;
  const sessionId = session.session_id ?? session.thread_id ?? session.id;
  if (!sessionId) return null;
  return {
    session_id: sessionId,
    thread_id: session.thread_id ?? sessionId,
    title: session.title ?? session.name ?? session.preview ?? '',
    cwd: session.cwd ?? '',
    model: session.model ?? '',
    preview: session.preview ?? '',
    active: session.active ?? true,
    backend: session.backend ?? '',
    createdAt: session.createdAt ?? '',
    updatedAt: session.updatedAt ?? '',
    ...(session.approvalPolicy != null ? { approvalPolicy: clone(session.approvalPolicy) } : {}),
    ...(session.permissions != null ? { permissions: clone(session.permissions) } : {}),
    ...(session.sandbox != null ? { sandbox: clone(session.sandbox) } : {}),
    ...(session.contextWindow != null ? { contextWindow: session.contextWindow } : {}),
    ...(session.lastTokenUsage != null ? { lastTokenUsage: clone(session.lastTokenUsage) } : {}),
    ...(session.totalTokenUsage != null ? { totalTokenUsage: clone(session.totalTokenUsage) } : {}),
    ...(session.reasoningEffort != null ? { reasoningEffort: clone(session.reasoningEffort) } : {}),
  };
}

function inferThreadHistoryUpdatedAt(thread) {
  const turns = Array.isArray(thread?.turns) ? thread.turns : [];
  let latestSeconds = 0;
  for (const turn of turns) {
    const candidates = [turn?.completedAt, turn?.startedAt, turn?.createdAt, turn?.updatedAt];
    for (const value of candidates) {
      if (Number.isFinite(value) && value > latestSeconds) {
        latestSeconds = value;
      }
    }
  }
  return latestSeconds > 0 ? new Date(latestSeconds * 1000).toISOString() : null;
}

export function getThreadStatusType(thread) {
  const status = thread?.status;
  if (!status) return null;
  return typeof status === 'string' ? status : status.type ?? null;
}

export function hasMeaningfulSessionEvents(session) {
  const events = Array.isArray(session?.events) ? session.events : [];
  return events.some((event) => {
    const name = String(event?.event ?? '');
    return name !== 'thread/started'
      && name !== 'session/changed'
      && name !== 'thread/tokenUsage/updated'
      && name !== 'thread/status/changed'
      && name !== 'warning'
      && name !== 'error';
  });
}

export function isThreadNotFoundError(error) {
  const message = String(error?.message ?? '');
  return /thread not found/i.test(message);
}

export function normalizeTokenUsageSnapshot(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') return null;

  const normalized = {
    totalTokens: Number(snapshot.totalTokens ?? snapshot.total_tokens ?? 0),
    inputTokens: Number(snapshot.inputTokens ?? snapshot.input_tokens ?? 0),
    cachedInputTokens: Number(snapshot.cachedInputTokens ?? snapshot.cached_input_tokens ?? 0),
    outputTokens: Number(snapshot.outputTokens ?? snapshot.output_tokens ?? 0),
    reasoningOutputTokens: Number(snapshot.reasoningOutputTokens ?? snapshot.reasoning_output_tokens ?? 0),
  };

  if (!Object.values(normalized).some((value) => Number.isFinite(value) && value > 0)) {
    return null;
  }

  return normalized;
}

export function hasMissingSessionMetadata(session) {
  if (!session) return false;
  return !firstNonEmptyString(session.model)
    || !firstNonEmptyString(session.approvalPolicy)
    || session.sandbox == null
    || !Number.isFinite(session.contextWindow)
    || session.contextWindow <= 0;
}

export function mergeMetadataIntoSession(session, metadata = {}) {
  if (!session) return null;
  const next = {
    ...clone(session),
  };

  if (firstNonEmptyString(metadata.model) && !firstNonEmptyString(next.model)) {
    next.model = metadata.model;
  }

  if (firstNonEmptyString(metadata.approvalPolicy) && !firstNonEmptyString(next.approvalPolicy)) {
    next.approvalPolicy = metadata.approvalPolicy;
  }

  if (metadata.permissions != null && next.permissions == null) {
    next.permissions = clone(metadata.permissions);
  }

  if (metadata.sandbox != null && next.sandbox == null) {
    next.sandbox = clone(metadata.sandbox);
  }

  if (Number.isFinite(metadata.contextWindow) && metadata.contextWindow > 0 && (!Number.isFinite(next.contextWindow) || next.contextWindow <= 0)) {
    next.contextWindow = metadata.contextWindow;
  }

  if (metadata.lastTokenUsage != null && next.lastTokenUsage == null) {
    next.lastTokenUsage = clone(metadata.lastTokenUsage);
  }

  if (metadata.totalTokenUsage != null && next.totalTokenUsage == null) {
    next.totalTokenUsage = clone(metadata.totalTokenUsage);
  }

  if (firstNonEmptyString(metadata.reasoningEffort) && !firstNonEmptyString(next.reasoningEffort)) {
    next.reasoningEffort = metadata.reasoningEffort;
  }

  return next;
}

export function defaultSandboxPolicy(env = process.env) {
  const raw = env?.CODEX_REMOTE_DEFAULT_SANDBOX;
  if (raw == null || String(raw).trim() === '') {
    return { type: 'dangerFullAccess' };
  }
  const policy = normalizeSandboxPolicy(String(raw).trim());
  if (!policy) {
    // Do not fall back silently: a typo here would silently grant full access.
    const error = new Error(`invalid CODEX_REMOTE_DEFAULT_SANDBOX: ${raw}`);
    error.code = 'INVALID_DEFAULT_SANDBOX';
    throw error;
  }
  return policy;
}
