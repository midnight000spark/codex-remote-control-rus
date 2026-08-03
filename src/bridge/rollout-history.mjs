import fs from 'node:fs/promises';
import { createEvent } from '../protocol.mjs';
import { clone, firstNonEmptyString, safeJsonLineParse } from './common.mjs';
import { normalizeTokenUsageSnapshot } from './session-normalization.mjs';

const rolloutMessageTimestampCache = new Map();

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

    return metadata;
  } catch {
    return {};
  }
}

export async function buildEventsFromRolloutFile(sessionId, filePath, threadId = sessionId) {
  const raw = await fs.readFile(filePath, 'utf8');
  const lines = raw.split('\n').map((line) => line.trim()).filter(Boolean);
  const events = [];
  let currentTurnId = null;
  let currentItemSeq = 0;

  for (const line of lines) {
    const record = safeJsonLineParse(line);
    if (!record || typeof record !== 'object') continue;
    const payload = record.payload ?? {};
    if (record.type === 'event_msg') {
      switch (payload.type) {
        case 'task_started': {
          currentTurnId = payload.turn_id ?? currentTurnId;
          currentItemSeq = 0;
          if (currentTurnId) {
            events.push(createEvent('turn/started', {
              session_id: sessionId,
              thread_id: threadId,
              turn_id: currentTurnId,
              payload: { turn: { id: currentTurnId } },
            }));
          }
          break;
        }
        case 'user_message': {
          const text = String(payload.message ?? '').trim();
          if (text) {
            const itemId = currentTurnId ? `rollout_${currentTurnId}_user` : `rollout_${sessionId}_user`;
            events.push(createEvent('turn/input', {
              session_id: sessionId,
              thread_id: threadId,
              turn_id: currentTurnId,
              payload: {
                itemId,
                item_id: itemId,
                text,
                input: null,
              },
            }));
          }
          break;
        }
        case 'agent_message': {
          const text = String(payload.message ?? '').trim();
          if (text) {
            events.push(createEvent('item/completed', {
              session_id: sessionId,
              thread_id: threadId,
              turn_id: currentTurnId,
              payload: {
                item: {
                  type: 'agentMessage',
                  id: currentTurnId ? `rollout_${currentTurnId}_${String(++currentItemSeq).padStart(4, '0')}` : `rollout_${sessionId}_${String(++currentItemSeq).padStart(4, '0')}`,
                  text,
                  phase: null,
                  memoryCitation: null,
                },
              },
            }));
          }
          break;
        }
        case 'task_complete': {
          const completedTurnId = payload.turn_id ?? currentTurnId;
          if (completedTurnId) {
            events.push(createEvent('turn/completed', {
              session_id: sessionId,
              thread_id: threadId,
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
          currentTurnId = null;
          break;
        }
        default:
          break;
      }
    }
  }

  return events;
}

export async function buildContentEntriesFromRolloutFile(sessionId, filePath, threadId = sessionId) {
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

  return entries;
}
