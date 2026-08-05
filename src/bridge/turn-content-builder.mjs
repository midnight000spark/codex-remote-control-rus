import { createEvent } from '../protocol.mjs';
import {
  extractUserMessageText,
  getTurnInputItemId,
  normalizeUserMessageContent,
  normalizeTurnStatusValue,
  userMessageContentSignature,
} from './common.mjs';
import { SYNC_LOG_EVENT_LIMIT, summarizeSyncEntries, syncItemTypeCounts } from './sync-log.mjs';
import {
  eventDedupeSignatures,
  eventSignature,
  mergeInitialSessionEvents,
  pruneHydratedEvents,
} from './session-event-signature.mjs';
import { readRolloutMessageTimestamps } from './rollout-history.mjs';

const CONVERSATION_ITEM_TYPES = new Set([
  'userMessage',
  'agentMessage',
  'fileChange',
  'imageView',
  'imageGeneration',
]);

function isConversationItem(item) {
  return CONVERSATION_ITEM_TYPES.has(item?.type);
}

function normalizeEntryTimestamp(value) {
  if (value == null || value === '') return null;
  const numeric = Number(value);
  const millis = Number.isFinite(numeric)
    ? (numeric < 100_000_000_000 ? numeric * 1000 : numeric)
    : Date.parse(String(value));
  return Number.isFinite(millis) && millis > 0 ? new Date(millis).toISOString() : null;
}

function rolloutTimestampKey(turnId, itemType, text) {
  return JSON.stringify([turnId ?? '', itemType ?? '', String(text ?? '').replace(/\r\n/g, '\n').trim()]);
}

export class TurnContentBuilder {
  constructor({ backend, store, loadHydrationThread, logSync = () => {} }) {
    this.backend = backend;
    this.store = store;
    this.loadHydrationThread = loadHydrationThread;
    this.logSync = logSync;
  }

  #logSync(event, details = {}) {
    this.logSync(event, details);
  }
  buildContentEntriesFromStoredEvents(sessionId) {
    const session = this.store.getSession(sessionId);
    if (!session) return [];

    const events = this.store.syncEvents(sessionId, 0);
    const entries = [];
    const seenItemIds = new Set();
    const completedUserMessageSignatures = new Set();

    for (const event of events) {
      if (event?.event !== 'item/completed') continue;
      const item = event?.payload?.item;
      if (item?.type !== 'userMessage') continue;
      const content = normalizeUserMessageContent(item.content);
      if (content.length === 0) continue;
      completedUserMessageSignatures.add(`${event.turn_id ?? ''}|${userMessageContentSignature(content)}`);
    }

    for (const event of events) {
      if (event?.event === 'turn/input') {
        const content = normalizeUserMessageContent(event?.payload?.input, event?.payload?.text);
        if (content.length === 0) continue;
        if (completedUserMessageSignatures.has(`${event.turn_id ?? ''}|${userMessageContentSignature(content)}`)) {
          continue;
        }
        const itemId = getTurnInputItemId({
          payload: event?.payload,
          requestId: event?.request_id ?? '',
          turnId: event?.turn_id ?? '',
          sessionId,
          seq: event?.seq ?? entries.length,
        });
        if (seenItemIds.has(itemId)) continue;
        seenItemIds.add(itemId);
        const at = normalizeEntryTimestamp(event.at);
        entries.push({
          type: 'item',
          session_id: sessionId,
          thread_id: session.thread_id ?? sessionId,
          turn_id: event.turn_id ?? null,
          ...(at ? { at } : {}),
          item: {
            type: 'userMessage',
            id: itemId,
            content,
          },
        });
        continue;
      }

      if (event?.event !== 'item/completed') continue;
      const item = event?.payload?.item;
      if (!item?.type) continue;
      if (!isConversationItem(item)) continue;

      const itemId = item.id ?? `stored_item_${event.seq ?? entries.length}`;
      if (seenItemIds.has(itemId)) continue;
      seenItemIds.add(itemId);
      const at = normalizeEntryTimestamp(event.at);
      entries.push({
        type: 'item',
        session_id: sessionId,
        thread_id: session.thread_id ?? sessionId,
        turn_id: event.turn_id ?? null,
        ...(at ? { at } : {}),
        item: {
          ...item,
          id: itemId,
        },
      });
    }

    return entries;
  }

  mergeStoredTurnInputEntries(sessionId, entries = []) {
    const session = this.store.getSession(sessionId);
    if (!session) return entries;

    const merged = entries.slice();
    const existingItemIds = new Set();
    const existingInputSignatures = new Set();
    for (const entry of merged) {
      const item = entry?.item;
      if (item?.id) existingItemIds.add(item.id);
      if (item?.type !== 'userMessage') continue;
      const content = normalizeUserMessageContent(item.content);
      if (content.length > 0) {
        existingInputSignatures.add(`${entry.turn_id ?? ''}|${userMessageContentSignature(content)}`);
      }
    }

    const events = this.store.syncEvents(sessionId, 0);
    for (const event of events) {
      if (event?.event !== 'turn/input') continue;
      const content = normalizeUserMessageContent(event?.payload?.input, event?.payload?.text);
      if (content.length === 0) continue;
      const signature = `${event.turn_id ?? ''}|${userMessageContentSignature(content)}`;
      const itemId = getTurnInputItemId({
        payload: event?.payload,
        requestId: event?.request_id ?? '',
        turnId: event?.turn_id ?? '',
        sessionId,
        seq: event?.seq ?? merged.length,
      });
      if (existingInputSignatures.has(signature) || existingItemIds.has(itemId)) continue;

      const at = normalizeEntryTimestamp(event.at);
      const entry = {
        type: 'item',
        session_id: sessionId,
        thread_id: session.thread_id ?? sessionId,
        turn_id: event.turn_id ?? null,
        ...(at ? { at } : {}),
        item: {
          type: 'userMessage',
          id: itemId,
          content,
        },
      };
      const index = this.#lastContentEntryIndexForTurn(merged, event.turn_id ?? '');
      if (index >= 0) {
        merged.splice(index + 1, 0, entry);
      } else {
        merged.push(entry);
      }
      existingInputSignatures.add(signature);
      existingItemIds.add(itemId);
    }

    return merged;
  }

  #lastContentEntryIndexForTurn(entries, turnId) {
    if (!turnId) return -1;
    for (let index = entries.length - 1; index >= 0; index -= 1) {
      if (entries[index]?.turn_id === turnId) return index;
    }
    return -1;
  }

  #buildStoredItemTimestampIndex(sessionId) {
    const session = this.store.getSession(sessionId);
    const timestamps = new Map();
    for (const event of session?.events ?? []) {
      let itemId = null;
      if (event?.event === 'turn/input') {
        itemId = getTurnInputItemId({
          payload: event?.payload,
          requestId: event?.request_id ?? '',
          turnId: event?.turn_id ?? '',
          sessionId,
          seq: event?.seq ?? 0,
        });
      } else if (event?.event === 'item/completed') {
        itemId = event?.payload?.item?.id ?? null;
      }
      const timestamp = normalizeEntryTimestamp(event?.at);
      if (itemId && timestamp && !timestamps.has(itemId)) {
        timestamps.set(itemId, timestamp);
      }
    }
    return timestamps;
  }

  async #buildRolloutTimestampQueues(sessionId) {
    const filePath = this.store.getSession(sessionId)?.thread?.path;
    if (!filePath) return new Map();
    try {
      const queues = new Map();
      for (const message of await readRolloutMessageTimestamps(filePath)) {
        const key = rolloutTimestampKey(message.turnId, message.itemType, message.text);
        const queue = queues.get(key) ?? [];
        queue.push(message.at);
        queues.set(key, queue);
      }
      return queues;
    } catch {
      return new Map();
    }
  }

  #takeRolloutItemTimestamp(queues, turnId, item) {
    const text = item?.type === 'userMessage'
      ? extractUserMessageText(normalizeUserMessageContent(item?.content))
      : item?.type === 'agentMessage' ? item?.text ?? '' : '';
    if (!text) return null;
    const queue = queues.get(rolloutTimestampKey(turnId, item.type, text));
    return queue?.shift() ?? null;
  }

  #resolveItemTimestamp(storedTimestamps, itemId, turn, item, rolloutTimestamp = null) {
    const itemTimestamp = normalizeEntryTimestamp(item?.createdAt ?? item?.timestamp ?? item?.completedAt);
    if (itemTimestamp) return itemTimestamp;
    const exactRolloutTimestamp = normalizeEntryTimestamp(rolloutTimestamp);
    if (exactRolloutTimestamp) return exactRolloutTimestamp;
    const storedTimestamp = itemId ? storedTimestamps.get(itemId) : null;
    if (storedTimestamp) return storedTimestamp;
    const turnTimestamp = item?.type === 'userMessage'
      ? turn?.startedAt ?? turn?.createdAt
      : turn?.completedAt ?? turn?.startedAt ?? turn?.createdAt;
    return normalizeEntryTimestamp(turnTimestamp);
  }

  async buildEventsFromThread(sessionId, thread) {
    const rawThread = thread?.thread ?? thread;
    const turns = this.#sortTurnsForRendering(Array.isArray(rawThread?.turns) ? rawThread.turns : []);
    const threadId = rawThread?.id ?? rawThread?.threadId ?? rawThread?.sessionId ?? sessionId;
    const storedUserInputItemIds = this.#buildStoredTurnInputItemIdIndex(sessionId);
    const events = [];

    for (const turn of turns) {
      if (!turn?.id) continue;
      events.push(createEvent('turn/started', {
        session_id: sessionId,
        thread_id: threadId,
        turn_id: turn.id,
        payload: { turn: { id: turn.id } },
      }));

      const items = await this.#loadTurnItems(threadId, turn);
      let turnItemSeq = 0;
      for (const item of items) {
        if (item?.type === 'userMessage') {
          const content = normalizeUserMessageContent(item.content);
          if (content.length > 0) {
            const text = extractUserMessageText(content);
            const fallbackSeq = item.id ? null : String(++turnItemSeq).padStart(4, '0');
            const itemId = this.#resolveStoredTurnInputItemId(storedUserInputItemIds, turn.id, content)
              ?? item.id
              ?? `turn_${turn.id}_user_${fallbackSeq}`;
            events.push(createEvent('turn/input', {
              session_id: sessionId,
              thread_id: threadId,
              turn_id: turn.id,
              payload: {
                itemId,
                item_id: itemId,
                text,
                input: content,
              },
            }));
          }
          continue;
        }

        if (item?.type) {
          const itemId = item.id ?? `turn_${turn.id}_item_${String(++turnItemSeq).padStart(4, '0')}`;
          events.push(createEvent('item/completed', {
            session_id: sessionId,
            thread_id: threadId,
            turn_id: turn.id,
            payload: {
              item: {
                ...item,
                id: itemId,
              },
            },
          }));
        }
      }

      if (turn.status && turn.status !== 'inProgress') {
        events.push(createEvent('turn/completed', {
          session_id: sessionId,
          thread_id: threadId,
          turn_id: turn.id,
          payload: {
            status: turn.status,
            turn: {
              id: turn.id,
              status: turn.status,
            },
          },
        }));
      }
    }

    return events;
  }

  async buildContentEntriesFromThread(sessionId, thread) {
    const rawThread = thread?.thread ?? thread;
    const turns = this.#sortTurnsForRendering(Array.isArray(rawThread?.turns) ? rawThread.turns : []);
    const threadId = rawThread?.id ?? rawThread?.threadId ?? rawThread?.sessionId ?? sessionId;
    const storedUserInputItemIds = this.#buildStoredTurnInputItemIdIndex(sessionId);
    const storedItemTimestamps = this.#buildStoredItemTimestampIndex(sessionId);
    const rolloutTimestampQueues = await this.#buildRolloutTimestampQueues(sessionId);
    const entries = [];

    for (const turn of turns) {
      if (!turn?.id) continue;
      const items = await this.#loadTurnItems(threadId, turn);
      let turnItemSeq = 0;
      for (const item of items) {
        if (!item?.type) continue;
        if (!isConversationItem(item)) {
          continue;
        }
        const content = item.type === 'userMessage' ? normalizeUserMessageContent(item.content) : [];
        const fallbackSeq = item.id ? null : String(++turnItemSeq).padStart(4, '0');
        const storedUserInputItemId = item.type === 'userMessage'
          ? this.#resolveStoredTurnInputItemId(storedUserInputItemIds, turn.id, content)
          : null;
        const fallbackItemId = item.type === 'userMessage'
          ? `turn_${turn.id}_user_${fallbackSeq}`
          : `turn_${turn.id}_item_${fallbackSeq}`;
        const itemId = item.type === 'userMessage'
          ? storedUserInputItemId ?? item.id ?? fallbackItemId
          : item.id ?? fallbackItemId;
        const rolloutTimestamp = this.#takeRolloutItemTimestamp(rolloutTimestampQueues, turn.id, item);
        const at = this.#resolveItemTimestamp(storedItemTimestamps, itemId, turn, item, rolloutTimestamp);
        entries.push({
          type: 'item',
          session_id: sessionId,
          thread_id: threadId,
          turn_id: turn.id,
          ...(at ? { at } : {}),
          item: {
            ...item,
            id: itemId,
          },
        });
      }

      const turnStatus = normalizeTurnStatusValue(turn.status);
      if (turnStatus && turnStatus !== 'inProgress') {
        entries.push({
          type: 'turn_status',
          session_id: sessionId,
          thread_id: threadId,
          turn_id: turn.id,
          status: turnStatus,
          error: turn.error ?? this.#storedTurnError(sessionId, turn.id) ?? null,
        });
      }
    }

    return entries;
  }

  async buildContentEntriesForTurns(sessionId, turnIds) {
    const session = this.store.getSession(sessionId);
    if (!session) {
      this.#logSync('session.sync.turns.missing_session', { session_id: sessionId, turn_ids: turnIds });
      return { entries: [], changedTurnIds: [], missingFinalAgentTurnIds: [] };
    }

    const uniqueTurnIds = Array.from(new Set(turnIds.map((turnId) => String(turnId ?? '').trim()).filter(Boolean)));
    if (uniqueTurnIds.length === 0) {
      this.#logSync('session.sync.turns.empty_turn_ids', { session_id: sessionId, turn_ids: turnIds });
      return { entries: [], changedTurnIds: [], missingFinalAgentTurnIds: [] };
    }

    const threadId = session.thread_id ?? sessionId;
    const thread = await this.loadHydrationThread(sessionId, session);
    const rawThread = thread?.thread ?? thread ?? {};
    const turns = this.#sortTurnsForRendering(Array.isArray(rawThread?.turns) ? rawThread.turns : []);
    const turnsById = new Map(turns.map((turn) => [turn?.id, turn]).filter(([turnId]) => turnId));
    this.#logSync('session.sync.turns.thread_loaded', {
      session_id: sessionId,
      thread_id: threadId,
      requested_turn_ids: uniqueTurnIds,
      thread_turns: turns.length,
      matching_turn_ids: uniqueTurnIds.filter((turnId) => turnsById.has(turnId)),
      thread_turn_tail: turns.slice(-SYNC_LOG_EVENT_LIMIT).map((turn) => ({
        id: turn?.id ?? '',
        items: Array.isArray(turn?.items) ? turn.items.length : null,
        items_view: turn?.itemsView ?? null,
        status: turn?.status?.type ?? turn?.status ?? '',
      })),
    });
    const storedUserInputItemIds = this.#buildStoredTurnInputItemIdIndex(sessionId);
    const storedItemTimestamps = this.#buildStoredItemTimestampIndex(sessionId);
    const rolloutTimestampQueues = await this.#buildRolloutTimestampQueues(sessionId);
    const entries = [];
    const changedTurnIds = [];
    const missingFinalAgentTurnIds = [];

    for (const turnId of uniqueTurnIds) {
      const turn = turnsById.get(turnId) ?? (this.turnRequiresAuthoritativeItems(sessionId, turnId) ? null : this.#buildStoredTurnStub(sessionId, turnId));
      if (!turn) {
        this.#logSync('session.sync.turn.missing_turn', {
          session_id: sessionId,
          thread_id: threadId,
          turn_id: turnId,
          requires_authoritative_items: this.turnRequiresAuthoritativeItems(sessionId, turnId),
          has_completed_assistant_event: this.#turnHasCompletedAssistantEvent(sessionId, turnId),
        });
        continue;
      }

      const requiresAuthoritativeItems = this.turnRequiresAuthoritativeItems(sessionId, turnId);
      const turnEntries = await this.#buildContentEntriesFromTurn(sessionId, threadId, turn, storedUserInputItemIds, {
        requireAuthoritativeItems: requiresAuthoritativeItems,
        storedItemTimestamps,
        rolloutTimestampQueues,
      });
      if (turnEntries == null) {
        this.#logSync('session.sync.turn.entries_null', {
          session_id: sessionId,
          thread_id: threadId,
          turn_id: turnId,
          requires_authoritative_items: requiresAuthoritativeItems,
        });
        missingFinalAgentTurnIds.push(turnId);
        continue;
      }
      this.#logSync('session.sync.turn.entries', {
        session_id: sessionId,
        thread_id: threadId,
        turn_id: turnId,
        requires_authoritative_items: requiresAuthoritativeItems,
        turn_status: turn?.status?.type ?? turn?.status ?? '',
        turn_items_view: turn?.itemsView ?? null,
        inline_items: Array.isArray(turn?.items) ? turn.items.length : null,
        entries: turnEntries.length,
        item_counts: syncItemTypeCounts(turnEntries),
        entry_tail: summarizeSyncEntries(turnEntries),
      });
      entries.push(...turnEntries);
      changedTurnIds.push(turnId);

      if (
        (
          this.#turnHasCompletedAssistantEvent(sessionId, turnId)
          || (requiresAuthoritativeItems && this.#isCompletedTurn(turn))
        )
        && !turnEntries.some((entry) => entry?.item?.type === 'agentMessage')
      ) {
        missingFinalAgentTurnIds.push(turnId);
      }
    }

    return { entries, changedTurnIds, missingFinalAgentTurnIds };
  }

  async #buildContentEntriesFromTurn(sessionId, threadId, turn, storedUserInputItemIds, options = {}) {
    const entries = [];
    const turnId = turn?.id ?? '';
    if (!turnId) {
      this.#logSync('session.sync.turn.build_entries.blank_turn_id', { session_id: sessionId, thread_id: threadId });
      return entries;
    }

    const items = options.requireAuthoritativeItems
      ? await this.#loadAuthoritativeTurnItems(threadId, turn)
      : await this.#loadTurnItems(threadId, turn);
    if (items == null) {
      this.#logSync('session.sync.turn.build_entries.items_null', {
        session_id: sessionId,
        thread_id: threadId,
        turn_id: turnId,
        require_authoritative_items: options.requireAuthoritativeItems ?? false,
      });
      return null;
    }
    this.#logSync('session.sync.turn.build_entries.items', {
      session_id: sessionId,
      thread_id: threadId,
      turn_id: turnId,
      require_authoritative_items: options.requireAuthoritativeItems ?? false,
      item_count: items.length,
      item_types: items.reduce((counts, item) => {
        const key = item?.type ?? 'unknown';
        counts[key] = (counts[key] ?? 0) + 1;
        return counts;
      }, {}),
      item_tail: items.slice(-SYNC_LOG_EVENT_LIMIT).map((item) => ({
        type: item?.type ?? '',
        id: item?.id ?? '',
        text_len: String(item?.text ?? item?.content?.[0]?.text ?? '').length,
        status: item?.status ?? '',
      })),
    });

    let turnItemSeq = 0;
    for (const item of items) {
      if (!item?.type) continue;
      if (!isConversationItem(item)) {
        continue;
      }
      const content = item.type === 'userMessage' ? normalizeUserMessageContent(item.content) : [];
      const fallbackSeq = item.id ? null : String(++turnItemSeq).padStart(4, '0');
      const storedUserInputItemId = item.type === 'userMessage'
        ? this.#resolveStoredTurnInputItemId(storedUserInputItemIds, turnId, content)
        : null;
      const fallbackItemId = item.type === 'userMessage'
        ? `turn_${turnId}_user_${fallbackSeq}`
        : `turn_${turnId}_item_${fallbackSeq}`;
      const itemId = item.type === 'userMessage'
        ? storedUserInputItemId ?? item.id ?? fallbackItemId
        : item.id ?? fallbackItemId;
      const at = this.#resolveItemTimestamp(
        options.storedItemTimestamps ?? this.#buildStoredItemTimestampIndex(sessionId),
        itemId,
        turn,
        item,
        this.#takeRolloutItemTimestamp(options.rolloutTimestampQueues ?? new Map(), turnId, item),
      );
      entries.push({
        type: 'item',
        session_id: sessionId,
        thread_id: threadId,
        turn_id: turnId,
        ...(at ? { at } : {}),
        item: {
          ...item,
          id: itemId,
        },
      });
    }

    const turnStatus = normalizeTurnStatusValue(turn.status);
    if (turnStatus && turnStatus !== 'inProgress') {
      entries.push({
        type: 'turn_status',
        session_id: sessionId,
        thread_id: threadId,
        turn_id: turnId,
        status: turnStatus,
        error: turn.error ?? this.#storedTurnError(sessionId, turnId) ?? null,
      });
    }

    return entries;
  }

  async #loadAuthoritativeTurnItems(threadId, turn) {
    const inlineItems = Array.isArray(turn?.items) ? turn.items : [];
    if (turn?.itemsView === 'full') {
      this.#logSync('session.sync.turn.authoritative_items.inline_full', {
        thread_id: threadId,
        turn_id: turn?.id ?? '',
        items: inlineItems.length,
      });
      return inlineItems;
    }
    if (typeof this.backend.listTurnItems !== 'function' || !turn?.id || !threadId) {
      this.#logSync('session.sync.turn.authoritative_items.unavailable', {
        thread_id: threadId,
        turn_id: turn?.id ?? '',
        has_list_turn_items: typeof this.backend.listTurnItems === 'function',
      });
      return null;
    }

    try {
      const loaded = [];
      let cursor = null;
      do {
        const page = await this.backend.listTurnItems(threadId, turn.id, {
          cursor,
          limit: 200,
          sortDirection: 'asc',
        });
        const data = Array.isArray(page) ? page : page?.data ?? [];
        loaded.push(...data);
        cursor = Array.isArray(page) ? null : page?.nextCursor ?? null;
      } while (cursor);
      this.#logSync('session.sync.turn.authoritative_items.loaded', {
        thread_id: threadId,
        turn_id: turn.id,
        items: loaded.length,
        item_types: loaded.reduce((counts, item) => {
          const key = item?.type ?? 'unknown';
          counts[key] = (counts[key] ?? 0) + 1;
          return counts;
        }, {}),
      });
      return loaded;
    } catch (error) {
      this.#logSync('session.sync.turn.authoritative_items.error', {
        thread_id: threadId,
        turn_id: turn.id,
        message: error?.message ?? String(error),
      });
      return null;
    }
  }

  #buildStoredTurnStub(sessionId, turnId) {
    const session = this.store.getSession(sessionId);
    if (!session) return null;

    const items = [];
    const seenItemIds = new Set();
    for (const event of session.events ?? []) {
      if ((event?.turn_id ?? '') !== turnId) continue;
      if (event.event === 'turn/input') {
        const content = normalizeUserMessageContent(event?.payload?.input, event?.payload?.text);
        if (content.length === 0) continue;
        const itemId = getTurnInputItemId({
          payload: event?.payload,
          requestId: event?.request_id ?? '',
          turnId,
          sessionId,
          seq: event?.seq ?? items.length,
        });
        if (seenItemIds.has(itemId)) continue;
        seenItemIds.add(itemId);
        items.push({
          type: 'userMessage',
          id: itemId,
          content,
        });
        continue;
      }

      if (event.event !== 'item/completed') continue;
      const item = event?.payload?.item;
      if (!item?.type) continue;
      if (!isConversationItem(item)) continue;
      const itemId = item.id ?? `stored_item_${event.seq ?? items.length}`;
      if (seenItemIds.has(itemId)) continue;
      seenItemIds.add(itemId);
      items.push({
        ...item,
        id: itemId,
      });
    }

    if (items.length === 0) return null;
    return {
      id: turnId,
      items,
      itemsView: 'full',
      status: this.#storedTurnStatus(sessionId, turnId),
      error: this.#storedTurnError(sessionId, turnId),
      startedAt: null,
      completedAt: null,
      durationMs: null,
    };
  }

  #storedTurnStatus(sessionId, turnId) {
    const session = this.store.getSession(sessionId);
    let status = null;
    for (const event of session?.events ?? []) {
      if ((event?.turn_id ?? '') !== turnId) continue;
      if (event.event === 'turn/started') {
        status = 'inProgress';
      } else if (event.event === 'turn/completed') {
        status = event?.payload?.status ?? event?.payload?.turn?.status ?? 'completed';
      }
    }
    return status;
  }

  #storedTurnError(sessionId, turnId) {
    const session = this.store.getSession(sessionId);
    let error = null;
    for (const event of session?.events ?? []) {
      if ((event?.turn_id ?? '') !== turnId || event?.event !== 'turn/completed') continue;
      error = event?.payload?.error ?? event?.payload?.turn?.error ?? error;
    }
    return error;
  }

  #turnHasCompletedAssistantEvent(sessionId, turnId) {
    const session = this.store.getSession(sessionId);
    return (session?.events ?? []).some((event) => (
      (event?.turn_id ?? '') === turnId
      && event?.event === 'item/completed'
      && event?.payload?.item?.type === 'agentMessage'
    ));
  }

  turnRequiresAuthoritativeItems(sessionId, turnId) {
    const session = this.store.getSession(sessionId);
    return (session?.events ?? []).some((event) => (
      (event?.turn_id ?? '') === turnId
      && (
        (event?.event === 'item/completed' && event?.payload?.item?.type === 'agentMessage')
        || event?.event === 'item/agentMessage/delta'
      )
    ));
  }

  #isCompletedTurn(turn) {
    const status = turn?.status?.type ?? turn?.status ?? '';
    return status === 'completed' || status === 'failed' || status === 'interrupted' || Boolean(turn?.completedAt);
  }

  #sortTurnsForRendering(turns) {
    return turns.slice().sort((left, right) => {
      const leftStarted = Number(left?.startedAt ?? left?.createdAt ?? 0);
      const rightStarted = Number(right?.startedAt ?? right?.createdAt ?? 0);
      if (leftStarted !== rightStarted) return leftStarted - rightStarted;

      const leftCompleted = Number(left?.completedAt ?? 0);
      const rightCompleted = Number(right?.completedAt ?? 0);
      if (leftCompleted !== rightCompleted) return leftCompleted - rightCompleted;

      return String(left?.id ?? '').localeCompare(String(right?.id ?? ''));
    });
  }

  #buildStoredTurnInputItemIdIndex(sessionId) {
    const session = this.store.getSession(sessionId);
    const events = session?.events ?? [];
    const byTurnAndInput = new Map();
    const unresolvedByInput = new Map();
    for (const event of events) {
      if (event?.event !== 'turn/input') continue;
      const turnId = event.turn_id ?? '';
      const content = normalizeUserMessageContent(event?.payload?.input, event?.payload?.text);
      const inputSignature = userMessageContentSignature(content);
      const itemId = getTurnInputItemId({ payload: event?.payload, requestId: event?.request_id ?? '' });
      if (content.length === 0 || !itemId) continue;
      if (turnId) {
        byTurnAndInput.set(`${turnId}|${inputSignature}`, itemId);
      } else {
        const queue = unresolvedByInput.get(inputSignature) ?? [];
        if (!queue.includes(itemId)) {
          queue.push(itemId);
        }
        unresolvedByInput.set(inputSignature, queue);
      }
    }
    return { byTurnAndInput, unresolvedByInput };
  }

  #resolveStoredTurnInputItemId(index, turnId, content) {
    if (!content || content.length === 0) return null;
    const inputSignature = userMessageContentSignature(content);
    const exact = turnId ? index.byTurnAndInput.get(`${turnId}|${inputSignature}`) : null;
    if (exact) return exact;
    const unresolved = index.unresolvedByInput.get(inputSignature);
    if (!unresolved || unresolved.length === 0) return null;
    const itemId = unresolved.shift() ?? null;
    if (unresolved.length === 0) {
      index.unresolvedByInput.delete(inputSignature);
    }
    return itemId;
  }

  async #loadTurnItems(threadId, turn) {
    const inlineItems = Array.isArray(turn?.items) ? turn.items : [];
    const canListTurnItems = typeof this.backend.listTurnItems === 'function';
    const shouldLoadRemoteItems = canListTurnItems && turn?.id && threadId && (turn?.itemsView !== 'full' || inlineItems.length === 0);
    if (!shouldLoadRemoteItems) {
      return inlineItems;
    }

    try {
      const loaded = [];
      let cursor = null;
      do {
        const page = await this.backend.listTurnItems(threadId, turn.id, {
          cursor,
          limit: 200,
          sortDirection: 'asc',
        });
        const data = Array.isArray(page) ? page : page?.data ?? [];
        loaded.push(...data);
        cursor = Array.isArray(page) ? null : page?.nextCursor ?? null;
      } while (cursor);

      return loaded.length > 0 ? loaded : inlineItems;
    } catch {
      return inlineItems;
    }
  }

  mergeInitialSessionEvents(hydratedEvents, storedEvents) {
    return mergeInitialSessionEvents(hydratedEvents, storedEvents);
  }

  pruneHydratedEvents(hydratedEvents, storedEvents) {
    return pruneHydratedEvents(hydratedEvents, storedEvents);
  }

  eventSignature(event) {
    return eventSignature(event);
  }

  eventDedupeSignatures(event) {
    return eventDedupeSignatures(event);
  }

}
