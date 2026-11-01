import fs from 'node:fs/promises';
import { createEvent } from '../protocol.mjs';
import { clone, firstNonEmptyString, safeJsonLineParse } from './common.mjs';
import { normalizeTokenUsageSnapshot } from './session-normalization.mjs';

const rolloutMessageTimestampCache = new Map();
const rolloutEventCache = new Map();
const rolloutContentEntriesCache = new Map();
const rolloutMetadataCache = new Map();
const MAX_CACHED_ROLLOUT_FILES = 16;
const ROLLOUT_CONTEXT_SCAN_BYTES = 64 * 1024;
const ROLLOUT_READ_CHUNK_BYTES = 256 * 1024;

function rememberCached(map, key, value) {
  map.delete(key);
  map.set(key, value);
  while (map.size > MAX_CACHED_ROLLOUT_FILES) {
    map.delete(map.keys().next().value);
  }
}

function emptyRolloutCursor(offset = 0) {
  return {
    offset,
    currentTurnId: null,
    currentItemSeq: 0,
    partialLine: Buffer.alloc(0),
  };
}

function cloneRolloutCursor(cursor) {
  return {
    offset: Math.max(0, Number(cursor?.offset) || 0),
    currentTurnId: cursor?.currentTurnId ?? null,
    currentItemSeq: Math.max(0, Number(cursor?.currentItemSeq) || 0),
    partialLine: Buffer.from(cursor?.partialLine ?? Buffer.alloc(0)),
  };
}

function processRolloutEventRecord(record, cursor, context, events, emit) {
  if (record?.type !== 'event_msg') return false;
  const payload = record.payload ?? {};
  switch (payload.type) {
    case 'task_started': {
      cursor.currentTurnId = payload.turn_id ?? cursor.currentTurnId;
      cursor.currentItemSeq = 0;
      if (emit && cursor.currentTurnId) {
        events.push(createEvent('turn/started', {
          session_id: context.sessionId,
          thread_id: context.threadId,
          turn_id: cursor.currentTurnId,
          payload: { turn: { id: cursor.currentTurnId } },
        }));
      }
      return true;
    }
    case 'user_message': {
      const text = String(payload.message ?? '').trim();
      if (emit && text) {
        const itemId = cursor.currentTurnId
          ? `rollout_${cursor.currentTurnId}_user`
          : `rollout_${context.sessionId}_user`;
        events.push(createEvent('turn/input', {
          session_id: context.sessionId,
          thread_id: context.threadId,
          turn_id: cursor.currentTurnId,
          payload: {
            itemId,
            item_id: itemId,
            text,
            input: null,
          },
        }));
      }
      return false;
    }
    case 'agent_message': {
      const text = String(payload.message ?? '').trim();
      if (text) {
        cursor.currentItemSeq += 1;
      }
      if (emit && text) {
        const itemId = cursor.currentTurnId
          ? `rollout_${cursor.currentTurnId}_${String(cursor.currentItemSeq).padStart(4, '0')}`
          : `rollout_${context.sessionId}_${String(cursor.currentItemSeq).padStart(4, '0')}`;
        events.push(createEvent('item/completed', {
          session_id: context.sessionId,
          thread_id: context.threadId,
          turn_id: cursor.currentTurnId,
          payload: {
            item: {
              type: 'agentMessage',
              id: itemId,
              text,
              phase: null,
              memoryCitation: null,
            },
          },
        }));
      }
      return false;
    }
    case 'task_complete': {
      const completedTurnId = payload.turn_id ?? cursor.currentTurnId;
      if (emit && completedTurnId) {
        events.push(createEvent('turn/completed', {
          session_id: context.sessionId,
          thread_id: context.threadId,
          turn_id: completedTurnId,
          payload: {
            status: 'completed',
            turn: {
              id: completedTurnId,
              status: 'completed',
            },
          },
        }));
      }
      cursor.currentTurnId = null;
      cursor.currentItemSeq = 0;
      return true;
    }
    default:
      return false;
  }
}

function consumeRolloutBytes(bytes, cursor, context, { emit, final }) {
  const data = cursor.partialLine.length > 0
    ? Buffer.concat([cursor.partialLine, bytes])
    : bytes;
  const events = [];
  let sawBoundary = false;
  let lineStart = 0;

  for (let index = 0; index < data.length; index += 1) {
    if (data[index] !== 0x0a) continue;
    const line = data.subarray(lineStart, index).toString('utf8').trim();
    const record = line ? safeJsonLineParse(line) : null;
    if (record) {
      sawBoundary = processRolloutEventRecord(record, cursor, context, events, emit) || sawBoundary;
    }
    lineStart = index + 1;
  }

  const remainder = data.subarray(lineStart);
  cursor.partialLine = Buffer.alloc(0);
  if (final && remainder.length > 0) {
    const line = remainder.toString('utf8').trim();
    const record = line ? safeJsonLineParse(line) : null;
    if (record) {
      sawBoundary = processRolloutEventRecord(record, cursor, context, events, emit) || sawBoundary;
    } else if (line) {
      cursor.partialLine = Buffer.from(remainder);
    }
  } else if (remainder.length > 0) {
    cursor.partialLine = Buffer.from(remainder);
  }

  return { events, sawBoundary };
}

async function readHandleRange(handle, start, end) {
  const length = Math.max(0, end - start);
  const buffer = Buffer.allocUnsafe(length);
  let readOffset = 0;
  while (readOffset < length) {
    const { bytesRead } = await handle.read(
      buffer,
      readOffset,
      length - readOffset,
      start + readOffset,
    );
    if (bytesRead <= 0) break;
    readOffset += bytesRead;
  }
  return buffer.subarray(0, readOffset);
}

async function initializeRolloutCursor(handle, endOffset, context) {
  if (endOffset <= 0) return emptyRolloutCursor(0);

  let windowBytes = Math.min(ROLLOUT_CONTEXT_SCAN_BYTES, endOffset);
  while (true) {
    const start = Math.max(0, endOffset - windowBytes);
    const readStart = start > 0 ? start - 1 : 0;
    const raw = await readHandleRange(handle, readStart, endOffset);
    let suffix = raw;
    if (start > 0) {
      if (raw[0] === 0x0a) {
        suffix = raw.subarray(1);
      } else {
        const newline = raw.indexOf(0x0a);
        suffix = newline >= 0 ? raw.subarray(newline + 1) : Buffer.alloc(0);
      }
    }

    const cursor = emptyRolloutCursor(endOffset);
    const { sawBoundary } = consumeRolloutBytes(suffix, cursor, context, {
      emit: false,
      final: false,
    });
    if (sawBoundary || start === 0) {
      return cursor;
    }
    windowBytes = Math.min(endOffset, windowBytes * 2);
  }
}

export async function createRolloutEventCursor(filePath, endOffset = null) {
  const handle = await fs.open(filePath, 'r');
  try {
    const stat = await handle.stat();
    const targetOffset = endOffset == null
      ? stat.size
      : Math.max(0, Math.min(Number(endOffset) || 0, stat.size));
    return await initializeRolloutCursor(handle, targetOffset, { sessionId: '', threadId: '' });
  } finally {
    await handle.close();
  }
}

export async function readRolloutEventsFromCursor(
  sessionId,
  filePath,
  threadId = sessionId,
  sourceCursor = emptyRolloutCursor(0),
  endOffset = null,
) {
  const context = { sessionId, threadId };
  const handle = await fs.open(filePath, 'r');
  try {
    const stat = await handle.stat();
    const targetOffset = endOffset == null
      ? stat.size
      : Math.max(0, Math.min(Number(endOffset) || 0, stat.size));
    if (targetOffset < (Number(sourceCursor?.offset) || 0)) {
      return {
        events: [],
        cursor: await initializeRolloutCursor(handle, targetOffset, context),
      };
    }

    const cursor = cloneRolloutCursor(sourceCursor);
    const events = [];
    let position = cursor.offset;
    while (position < targetOffset) {
      const end = Math.min(targetOffset, position + ROLLOUT_READ_CHUNK_BYTES);
      const bytes = await readHandleRange(handle, position, end);
      if (bytes.length === 0) break;
      position += bytes.length;
      const parsed = consumeRolloutBytes(bytes, cursor, context, {
        emit: true,
        final: position >= targetOffset,
      });
      events.push(...parsed.events);
    }
    cursor.offset = position;
    return { events, cursor };
  } finally {
    await handle.close();
  }
}

export async function readRolloutMessageTimestamps(filePath) {
  const stat = await fs.stat(filePath);
  const cacheKey = `${stat.size}:${stat.mtimeMs}`;
  const cached = rolloutMessageTimestampCache.get(filePath);
  if (cached?.cacheKey === cacheKey) {
    return cached.messages;
  }

  const raw = await fs.readFile(filePath, 'utf8');
  const messages = [];
  let currentTurnId = null;
  for (const line of raw.split('\n')) {
    const record = safeJsonLineParse(line.trim());
    if (!record || record.type !== 'event_msg') continue;
    const payload = record.payload ?? {};
    if (payload.type === 'task_started') {
      currentTurnId = payload.turn_id ?? currentTurnId;
    } else if (payload.type === 'task_complete') {
      currentTurnId = null;
    } else if (payload.type === 'user_message' || payload.type === 'agent_message') {
      const text = String(payload.message ?? '').replace(/\r\n/g, '\n').trim();
      if (currentTurnId && text && record.timestamp) {
        messages.push({
          turnId: currentTurnId,
          itemType: payload.type === 'user_message' ? 'userMessage' : 'agentMessage',
          text,
          at: record.timestamp,
        });
      }
    }
  }
  rolloutMessageTimestampCache.set(filePath, { cacheKey, messages });
  return messages;
}

export async function extractSessionMetadataFromRollout(session) {
  const rolloutPath = session?.thread?.path;
  if (typeof rolloutPath !== 'string' || !rolloutPath.trim()) {
    return {};
  }

  let stat;
  try {
    stat = await fs.stat(rolloutPath);
  } catch {
    return {};
  }
  const cached = rolloutMetadataCache.get(rolloutPath);
  if (cached && cached.size === stat.size && cached.mtimeMs === stat.mtimeMs) {
    rememberCached(rolloutMetadataCache, rolloutPath, cached);
    return cached.metadata;
  }

  try {
    const raw = await fs.readFile(rolloutPath, 'utf8');
    const metadata = {};
    const lines = raw.split('\n').map((line) => line.trim()).filter(Boolean);

    for (const line of lines) {
      const record = safeJsonLineParse(line);
      if (!record || typeof record !== 'object') continue;
      const payload = record.payload ?? {};

      if (record.type === 'session_meta' && firstNonEmptyString(payload.model)) {
        metadata.model = payload.model;
      }

      if (record.type === 'turn_context') {
        if (firstNonEmptyString(payload.model) && !firstNonEmptyString(metadata.model)) {
          metadata.model = payload.model;
        }
        if (firstNonEmptyString(payload.effort)) {
          metadata.reasoningEffort = payload.effort;
        }
        if (firstNonEmptyString(payload.approval_policy)) {
          metadata.approvalPolicy = payload.approval_policy;
        }
        if (payload.sandbox_policy != null) {
          metadata.sandbox = clone(payload.sandbox_policy);
        }
        if (payload.permission_profile != null) {
          metadata.permissions = clone(payload.permission_profile);
        }
        if (Number.isFinite(payload.model_context_window) && payload.model_context_window > 0) {
          metadata.contextWindow = payload.model_context_window;
        }
      }

      if (record.type === 'event_msg' && payload.type === 'task_started') {
        if (Number.isFinite(payload.model_context_window) && payload.model_context_window > 0) {
          metadata.contextWindow = payload.model_context_window;
        }
      }

      if (record.type === 'event_msg' && payload.type === 'token_count') {
        const info = payload.info ?? {};
        if (Number.isFinite(info.model_context_window) && info.model_context_window > 0) {
          metadata.contextWindow = info.model_context_window;
        }
        const lastTokenUsage = normalizeTokenUsageSnapshot(info.last_token_usage ?? info.lastTokenUsage ?? null);
        if (lastTokenUsage != null) {
          metadata.lastTokenUsage = lastTokenUsage;
        }
        const totalTokenUsage = normalizeTokenUsageSnapshot(info.total_token_usage ?? info.totalTokenUsage ?? null);
        if (totalTokenUsage != null) {
          metadata.totalTokenUsage = totalTokenUsage;
        }
      }
    }

    rememberCached(rolloutMetadataCache, rolloutPath, {
      size: stat.size,
      mtimeMs: stat.mtimeMs,
      metadata,
    });
    return metadata;
  } catch {
    return {};
  }
}

export async function buildEventsFromRolloutFile(
  sessionId,
  filePath,
  threadId = sessionId,
  { afterBytes = null } = {},
) {
  if (afterBytes != null) {
    const cursor = await createRolloutEventCursor(filePath, afterBytes);
    const result = await readRolloutEventsFromCursor(sessionId, filePath, threadId, cursor);
    return result.events;
  }

  let stat;
  try {
    stat = await fs.stat(filePath);
  } catch {
    return [];
  }
  const key = filePath;
  const cached = rolloutEventCache.get(key);
  if (cached && cached.size === stat.size && cached.mtimeMs === stat.mtimeMs) {
    rememberCached(rolloutEventCache, key, cached);
    return cached.events;
  }
  if (cached && stat.size > cached.size && cached.cursor) {
    try {
      const result = await readRolloutEventsFromCursor(sessionId, filePath, threadId, cached.cursor);
      const next = {
        size: stat.size,
        mtimeMs: stat.mtimeMs,
        cursor: result.cursor,
        events: cached.events.concat(result.events),
      };
      rememberCached(rolloutEventCache, key, next);
      return next.events;
    } catch {
      rolloutEventCache.delete(key);
    }
  }

  const result = await readRolloutEventsFromCursor(sessionId, filePath, threadId, emptyRolloutCursor(0));
  rememberCached(rolloutEventCache, key, {
    size: stat.size,
    mtimeMs: stat.mtimeMs,
    cursor: result.cursor,
    events: result.events,
  });
  return result.events;
}

export async function buildContentEntriesFromRolloutFile(sessionId, filePath, threadId = sessionId) {
  let stat;
  try {
    stat = await fs.stat(filePath);
  } catch {
    return [];
  }
  const cacheKey = `${sessionId}:${filePath}`;
  const cached = rolloutContentEntriesCache.get(cacheKey);
  if (cached && cached.size === stat.size && cached.mtimeMs === stat.mtimeMs) {
    rememberCached(rolloutContentEntriesCache, cacheKey, cached);
    return cached.entries;
  }
  const raw = await fs.readFile(filePath, 'utf8');
  const lines = raw.split('\n').map((line) => line.trim()).filter(Boolean);
  const entries = [];
  let currentTurnId = null;
  let currentItemSeq = 0;

  for (const line of lines) {
    const record = safeJsonLineParse(line);
    if (!record || typeof record !== 'object') continue;
    const payload = record.payload ?? {};
    if (record.type !== 'event_msg') continue;

    switch (payload.type) {
      case 'task_started':
        currentTurnId = payload.turn_id ?? currentTurnId;
        currentItemSeq = 0;
        break;
      case 'user_message': {
        const text = String(payload.message ?? '').trim();
        if (!text) break;
        entries.push({
          type: 'item',
          session_id: sessionId,
          thread_id: threadId,
          turn_id: currentTurnId,
          ...(record.timestamp ? { at: record.timestamp } : {}),
          item: {
            type: 'userMessage',
            id: currentTurnId ? `rollout_${currentTurnId}_user` : `rollout_${sessionId}_user`,
            content: [{ type: 'text', text }],
          },
        });
        break;
      }
      case 'agent_message': {
        const text = String(payload.message ?? '').trim();
        if (!text) break;
        entries.push({
          type: 'item',
          session_id: sessionId,
          thread_id: threadId,
          turn_id: currentTurnId,
          ...(record.timestamp ? { at: record.timestamp } : {}),
          item: {
            type: 'agentMessage',
            id: currentTurnId ? `rollout_${currentTurnId}_${String(++currentItemSeq).padStart(4, '0')}` : `rollout_${sessionId}_${String(++currentItemSeq).padStart(4, '0')}`,
            text,
            phase: null,
            memoryCitation: null,
          },
        });
        break;
      }
      default:
        break;
    }
  }

  rememberCached(rolloutContentEntriesCache, cacheKey, {
    size: stat.size,
    mtimeMs: stat.mtimeMs,
    entries,
  });
  return entries;
}
