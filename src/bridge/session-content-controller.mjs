import {
  backendName,
  clone,
} from './common.mjs';
import {
  defaultSandboxPolicy,
  getThreadStatusType,
  hasMeaningfulSessionEvents,
  hasMissingSessionMetadata,
  mergeMetadataIntoSession,
  mergeSessionSummary,
  normalizePermissionSelection,
  normalizeThreadSummary,
  toSandboxMode,
} from './session-normalization.mjs';
import {
  SESSION_SYNC_EVENT_NAMES,
  summarizeSyncEntries,
  summarizeSyncEvents,
  syncItemTypeCounts,
} from './sync-log.mjs';
import {
  buildContentEntriesFromRolloutFile,
  buildEventsFromRolloutFile,
  extractSessionMetadataFromRollout,
} from './rollout-history.mjs';
import { buildActiveTurns } from './active-turns.mjs';
import { TurnContentBuilder } from './turn-content-builder.mjs';

export class SessionContentController {
  constructor({ backend, store, logSync = () => {} }) {
    this.backend = backend;
    this.store = store;
    this.logSync = logSync;
    this.turnContent = new TurnContentBuilder({
      backend: this.backend,
      store: this.store,
      loadHydrationThread: (sessionId, session) => this.#loadHydrationThread(sessionId, session),
      logSync: (event, details) => this.#logSync(event, details),
    });
  }

  #logSync(event, details = {}) {
    this.logSync(event, details);
  }
  async ensureSessionLoaded(sessionId, params = {}) {
    const session = this.store.getSession(sessionId);
    if (!session) return null;
    if (getThreadStatusType(session.thread) !== 'notLoaded') {
      return { session, resumed: null };
    }
    return this.resumeStoredSession(sessionId, params);
  }

  async resumeStoredSession(sessionId, params = {}) {
    const session = this.store.getSession(sessionId);
    if (!session) return null;
    const explicitPath = Object.prototype.hasOwnProperty.call(params, 'path') ? params.path : null;
    const requestedSandbox = Object.prototype.hasOwnProperty.call(params, 'sandbox')
      ? params.sandbox
      : session.sandbox ?? defaultSandboxPolicy();
    const resumed = await this.backend.resumeThread(session.thread_id ?? sessionId, {
      threadId: session.thread_id ?? sessionId,
      path: explicitPath,
      model: params.model ?? session.model ?? null,
      cwd: params.cwd ?? session.cwd ?? null,
      approvalPolicy: params.approvalPolicy ?? null,
      approvalsReviewer: params.approvalsReviewer ?? null,
      sandbox: toSandboxMode(requestedSandbox),
      permissions: normalizePermissionSelection(params.permissions),
      personality: params.personality ?? null,
      excludeTurns: params.excludeTurns ?? null,
      persistExtendedHistory: false,
    });
    const normalized = normalizeThreadSummary(resumed, backendName(this.backend));
    const merged = mergeSessionSummary(normalized, session);
    await this.store.upsertSession(merged);
    return { session: merged, resumed };
  }

  async refreshSessionHistory(sessionId, sinceSeq, options = {}) {
    const session = this.store.getSession(sessionId);
    if (!session) {
      return { ok: true, appended: 0, skipped: 0, lastSeq: 0, events: [] };
    }

    const storedEvents = this.store.syncEvents(sessionId, 0);
    const knownTurnIds = new Set(storedEvents.map((event) => event?.turn_id ?? '').filter(Boolean));
    const openTurnIds = this.#openTurnIdsAtSeq(storedEvents, sinceSeq);
    const incrementalWindowTurnIds = this.#incrementalTurnWindowIds(storedEvents, sinceSeq);
    const lastKnownStoreTurnId = this.#lastTurnIdAtSeq(storedEvents, sinceSeq);

    try {
      const hydratedEvents = Array.isArray(options.hydratedEvents)
        ? options.hydratedEvents
        : await this.#hydrateSessionHistory(sessionId, options);
      const latestStoredEvents = this.store.syncEvents(sessionId, 0);
      const effectiveHydratedEvents = this.turnContent.pruneHydratedEvents(hydratedEvents, latestStoredEvents);
      const hydratedTurnIndexes = this.#turnIndexesFromEvents(effectiveHydratedEvents);
      const knownTurnIdsAtSince = this.#turnIdsAtSeq(storedEvents, sinceSeq);
      const lastKnownHydratedTurnIndex = this.#lastKnownHydratedTurnIndex(hydratedTurnIndexes, knownTurnIdsAtSince);
      const eligibleTurnIds = this.#sessionSyncHydrationTurnIds({
        hydratedTurnIndexes,
        sinceSeq,
        openTurnIds,
        incrementalWindowTurnIds,
        lastKnownHydratedTurnIndex,
        lastKnownStoreTurnId,
      });
      const seen = new Set(latestStoredEvents.flatMap((event) => this.turnContent.eventDedupeSignatures(event)));
      const appendedEvents = [];
      let observedLastSeq = this.store.getLastSeq(sessionId);
      let appended = 0;
      let skipped = 0;

      for (const event of effectiveHydratedEvents) {
        const currentLastSeq = this.store.getLastSeq(sessionId);
        if (currentLastSeq > observedLastSeq) {
          for (const concurrentEvent of this.store.syncEvents(sessionId, observedLastSeq)) {
            for (const signature of this.turnContent.eventDedupeSignatures(concurrentEvent)) {
              seen.add(signature);
            }
          }
          observedLastSeq = currentLastSeq;
        }
        const signatures = this.turnContent.eventDedupeSignatures(event);
        if (signatures.some((signature) => seen.has(signature))) continue;
        const turnId = event?.turn_id ?? '';
        const eligible = sinceSeq <= 0
          || !turnId
          || (
            eligibleTurnIds.has(turnId)
            && (
              turnId !== lastKnownStoreTurnId
              || this.#isSessionSyncContentEvent(event, options)
            )
          );
        if (!eligible) {
          skipped += 1;
          continue;
        }
        const stored = await this.store.appendEvent(sessionId, event);
        storedEvents.push(stored);
        appendedEvents.push(stored);
        for (const signature of signatures) {
          seen.add(signature);
        }
        observedLastSeq = stored.seq ?? observedLastSeq;
        appended += 1;
      }

      this.#logSync('session.sync.refresh_history', {
        session_id: sessionId,
        since_seq: sinceSeq,
        hydrated_events: effectiveHydratedEvents.length,
        known_turns: knownTurnIds.size,
        incremental_window_turns: Array.from(incrementalWindowTurnIds),
        last_known_hydrated_turn_index: lastKnownHydratedTurnIndex,
        last_known_store_turn_id: lastKnownStoreTurnId,
        eligible_turn_ids: Array.from(eligibleTurnIds),
        appended,
        skipped_historical: skipped,
        last_seq: this.store.getLastSeq(sessionId),
      });
      return {
        ok: true,
        appended,
        skipped,
        lastSeq: this.store.getLastSeq(sessionId),
        events: appendedEvents,
      };
    } catch (error) {
      this.#logSync('session.sync.refresh_history.error', {
        session_id: sessionId,
        since_seq: sinceSeq,
        message: error?.message ?? String(error),
      });
      return {
        ok: false,
        appended: 0,
        skipped: 0,
        lastSeq: this.store.getLastSeq(sessionId),
        events: [],
      };
    }
  }

  #sessionSyncHydrationTurnIds({
    hydratedTurnIndexes,
    sinceSeq,
    openTurnIds,
    incrementalWindowTurnIds,
    lastKnownHydratedTurnIndex,
    lastKnownStoreTurnId,
  }) {
    const turnIds = new Set([...openTurnIds, ...incrementalWindowTurnIds]);
    if (sinceSeq <= 0) {
      for (const turnId of hydratedTurnIndexes.keys()) {
        turnIds.add(turnId);
      }
      return turnIds;
    }

    if (lastKnownStoreTurnId && hydratedTurnIndexes.has(lastKnownStoreTurnId)) {
      turnIds.add(lastKnownStoreTurnId);
    }
    if (lastKnownHydratedTurnIndex >= 0) {
      for (const [turnId, index] of hydratedTurnIndexes.entries()) {
        if (index > lastKnownHydratedTurnIndex) {
          turnIds.add(turnId);
        }
      }
    }
    return turnIds;
  }

  #isSessionSyncContentEvent(event, options = {}) {
    return event?.event === 'turn/input'
      || event?.event === 'item/completed'
      || (options.includeTailCompletion && event?.event === 'turn/completed')
      || event?.event === 'item/fileChange/patchUpdated'
      || event?.event === 'turn/diff/updated';
  }

  #turnIndexesFromEvents(events) {
    const indexes = new Map();
    for (const event of events) {
      const turnId = event?.turn_id ?? '';
      if (!turnId || indexes.has(turnId)) continue;
      indexes.set(turnId, indexes.size);
    }
    return indexes;
  }

  #turnIdsAtSeq(events, seq) {
    const turnIds = new Set();
    for (const event of events) {
      if ((event?.seq ?? 0) > seq) continue;
      const turnId = event?.turn_id ?? '';
      if (turnId) {
        turnIds.add(turnId);
      }
    }
    return turnIds;
  }

  #lastTurnIdAtSeq(events, seq) {
    for (let index = events.length - 1; index >= 0; index -= 1) {
      if ((events[index]?.seq ?? 0) > seq) continue;
      const turnId = events[index]?.turn_id ?? '';
      if (turnId) return turnId;
    }
    return '';
  }

  #lastKnownHydratedTurnIndex(hydratedTurnIndexes, knownTurnIds) {
    let lastIndex = -1;
    for (const turnId of knownTurnIds) {
      const index = hydratedTurnIndexes.get(turnId);
      if (Number.isInteger(index) && index > lastIndex) {
        lastIndex = index;
      }
    }
    return lastIndex;
  }

  #openTurnIdsAtSeq(events, seq) {
    const openTurnIds = new Set();
    for (const event of events.slice().sort((left, right) => (left.seq ?? 0) - (right.seq ?? 0))) {
      if ((event?.seq ?? 0) > seq) break;
      const turnId = event?.turn_id ?? '';
      if (!turnId) continue;
      if (event.event === 'turn/started') {
        openTurnIds.add(turnId);
      } else if (event.event === 'turn/completed') {
        openTurnIds.delete(turnId);
      }
    }
    return openTurnIds;
  }

  async #backfillSessionMetadataFromLocalState(session) {
    if (!session || !hasMissingSessionMetadata(session)) {
      return session;
    }

    let next = mergeMetadataIntoSession(session, this.#extractSessionMetadataFromEvents(session));
    next = mergeMetadataIntoSession(next, await extractSessionMetadataFromRollout(next));

    if (JSON.stringify(next) === JSON.stringify(session)) {
      return session;
    }

    return this.store.upsertSession(next);
  }

  #extractSessionMetadataFromEvents(session) {
    const metadata = {};
    const events = Array.isArray(session?.events) ? session.events : [];

    for (let index = events.length - 1; index >= 0; index -= 1) {
      const event = events[index];
      if (event?.event !== 'thread/tokenUsage/updated') continue;
      const tokenUsage = event?.payload?.tokenUsage;
      if (!tokenUsage || typeof tokenUsage !== 'object') continue;
      if (tokenUsage.modelContextWindow != null) {
        metadata.contextWindow = tokenUsage.modelContextWindow;
      }
      if (tokenUsage.last != null) {
        metadata.lastTokenUsage = tokenUsage.last;
      }
      if (tokenUsage.total != null) {
        metadata.totalTokenUsage = tokenUsage.total;
      }
      break;
    }

    return metadata;
  }


  async buildSessionContent(sessionId) {
    let session = this.store.getSession(sessionId);
    if (!session) {
      this.#logSync('session.content.build.missing_session', { session_id: sessionId });
      return {
        session_id: sessionId,
        thread_id: sessionId,
        last_seq: 0,
        entries: [],
        active_turns: [],
        pending_approvals: [],
      };
    }

    session = await this.#backfillSessionMetadataFromLocalState(session);

    let thread = null;
    try {
      thread = await this.#loadHydrationThread(sessionId, session);
      session = this.store.getSession(sessionId) ?? session;
      session = await this.#backfillSessionMetadataFromLocalState(session);
    } catch (error) {
      this.#logSync('session.content.hydration.error', {
        session_id: sessionId,
        message: error?.message ?? String(error),
      });
      thread = session.thread ?? null;
    }

    let entries = await this.turnContent.buildContentEntriesFromThread(sessionId, thread);
    this.#logSync('session.content.entries.thread', {
      session_id: sessionId,
      entries: entries.length,
      item_counts: syncItemTypeCounts(entries),
      entry_tail: summarizeSyncEntries(entries),
    });
    if (entries.length === 0) {
      entries = this.turnContent.buildContentEntriesFromStoredEvents(sessionId);
      this.#logSync('session.content.entries.stored_events', {
        session_id: sessionId,
        entries: entries.length,
        item_counts: syncItemTypeCounts(entries),
        entry_tail: summarizeSyncEntries(entries),
      });
    }
    if (entries.length === 0 && session.thread?.path) {
      try {
        entries = await buildContentEntriesFromRolloutFile(sessionId, session.thread.path, session.thread_id ?? sessionId);
        this.#logSync('session.content.entries.rollout', {
          session_id: sessionId,
          path: session.thread.path,
          entries: entries.length,
          item_counts: syncItemTypeCounts(entries),
          entry_tail: summarizeSyncEntries(entries),
        });
      } catch (error) {
        this.#logSync('session.content.entries.rollout_error', {
          session_id: sessionId,
          path: session.thread.path,
          message: error?.message ?? String(error),
        });
      }
    }
    entries = this.turnContent.mergeStoredTurnInputEntries(sessionId, entries);

    return {
      session_id: sessionId,
      thread_id: session.thread_id ?? sessionId,
      last_seq: this.store.getLastSeq(sessionId),
      session,
      entries,
      active_turns: buildActiveTurns(this.store, sessionId, entries),
      pending_approvals: this.store.getPendingApprovals(sessionId),
    };
  }


  #changedTurnIdsForSessionSync(sessionId, sinceSeq) {
    const session = this.store.getSession(sessionId);
    if (!session) {
      this.#logSync('session.sync.changed_turns.missing_session', { session_id: sessionId, since_seq: sinceSeq });
      return { turnIds: [], hasRelevantEvents: false };
    }

    const turnIds = new Set();
    let hasRelevantEvents = false;
    const events = (session.events ?? []).slice().sort((left, right) => (left.seq ?? 0) - (right.seq ?? 0));
    const incrementalTurnIds = this.#incrementalTurnWindowIds(events, sinceSeq);
    const tailTurnIdAtSince = this.#lastTurnIdAtSeq(events, sinceSeq);
    const relevantEvents = [];
    for (const event of events) {
      if ((event?.seq ?? 0) <= sinceSeq) continue;
      if (!SESSION_SYNC_EVENT_NAMES.has(event?.event)) continue;
      relevantEvents.push(event);
      const turnId = event?.turn_id ?? '';
      if (!turnId) {
        hasRelevantEvents = true;
        continue;
      }
      if (
        (incrementalTurnIds.has(turnId) || turnId === tailTurnIdAtSince)
        && this.#turnChangedAfterSeq(sessionId, turnId, sinceSeq)
      ) {
        hasRelevantEvents = true;
        turnIds.add(turnId);
      }
    }

    const result = { turnIds: Array.from(turnIds), hasRelevantEvents };
    this.#logSync('session.sync.changed_turns', {
      session_id: sessionId,
      since_seq: sinceSeq,
      last_seq: session.lastSeq ?? 0,
      relevant_events: relevantEvents.length,
      incremental_turn_ids: Array.from(incrementalTurnIds),
      tail_turn_id_at_since: tailTurnIdAtSince,
      turn_ids: result.turnIds,
      has_relevant_events: hasRelevantEvents,
      event_tail: summarizeSyncEvents(relevantEvents),
    });
    return result;
  }

  #incrementalTurnWindowIds(events, sinceSeq) {
    if (sinceSeq <= 0) {
      return new Set(events.map((event) => event?.turn_id ?? '').filter(Boolean));
    }

    const openTurnIds = new Set();
    for (const event of events) {
      if ((event?.seq ?? 0) > sinceSeq) break;
      const turnId = event?.turn_id ?? '';
      if (!turnId) continue;
      if (event.event === 'turn/started') {
        openTurnIds.add(turnId);
      } else if (event.event === 'turn/completed') {
        openTurnIds.delete(turnId);
      }
    }

    const turnIds = new Set(openTurnIds);
    for (const event of events) {
      if ((event?.seq ?? 0) <= sinceSeq) continue;
      const turnId = event?.turn_id ?? '';
      if (!turnId) continue;
      if (event.event === 'turn/started' || event.event === 'turn/input') {
        turnIds.add(turnId);
      }
    }

    return turnIds;
  }

  #turnChangedAfterSeq(sessionId, turnId, sinceSeq) {
    const session = this.store.getSession(sessionId);
    return (session?.events ?? []).some((event) => {
      if ((event?.seq ?? 0) <= sinceSeq) return false;
      if ((event?.turn_id ?? '') !== turnId) return false;
      return event?.event === 'turn/input'
        || event?.event === 'item/completed'
        || event?.event === 'turn/completed'
        || event?.event === 'item/fileChange/patchUpdated'
        || event?.event === 'turn/diff/updated';
    });
  }

  async buildSessionSync(sessionId, sinceSeq) {
    const session = this.store.getSession(sessionId);
    if (!session) {
      this.#logSync('session.sync.build.missing_session', { session_id: sessionId, since_seq: sinceSeq });
      return {
        session_id: sessionId,
        thread_id: sessionId,
        last_seq: 0,
        entries: [],
        active_turns: [],
        pending_approvals: [],
        changed_turn_ids: [],
        needs_full_sync: false,
        fallback_reason: '',
      };
    }

    if (sinceSeq <= 0) {
      // Initial sync used to hydrate every turn with a separate JSON-RPC call and
      // then return only the changed subset. Ask the client for full content
      // instead: it is complete and avoids dozens of round trips on first load.
      return {
        session_id: sessionId,
        thread_id: session.thread_id ?? sessionId,
        last_seq: this.store.getLastSeq(sessionId),
        entries: [],
        active_turns: buildActiveTurns(this.store, sessionId, []),
        pending_approvals: this.store.getPendingApprovals(sessionId),
        changed_turn_ids: [],
        needs_full_sync: true,
        fallback_reason: 'initial_sync_requires_full_content',
      };
    }

    let { turnIds, hasRelevantEvents } = this.#changedTurnIdsForSessionSync(sessionId, sinceSeq);
    if (!hasRelevantEvents || turnIds.length === 0) {
      await this.refreshSessionHistory(sessionId, sinceSeq);
      ({ turnIds, hasRelevantEvents } = this.#changedTurnIdsForSessionSync(sessionId, sinceSeq));
    }
    const refreshedLastSeq = this.store.getLastSeq(sessionId);
    if (!hasRelevantEvents) {
      this.#logSync('session.sync.build.no_relevant_events', {
        session_id: sessionId,
        since_seq: sinceSeq,
        last_seq: refreshedLastSeq,
      });
      return {
        session_id: sessionId,
        thread_id: session.thread_id ?? sessionId,
        last_seq: refreshedLastSeq,
        entries: [],
        active_turns: buildActiveTurns(this.store, sessionId, []),
        pending_approvals: this.store.getPendingApprovals(sessionId),
        changed_turn_ids: [],
        needs_full_sync: false,
        fallback_reason: '',
      };
    }

    if (turnIds.length === 0) {
      this.#logSync('session.sync.build.no_turn_ids', {
        session_id: sessionId,
        since_seq: sinceSeq,
        last_seq: refreshedLastSeq,
      });
      return {
        session_id: sessionId,
        thread_id: session.thread_id ?? sessionId,
        last_seq: refreshedLastSeq,
        entries: [],
        active_turns: buildActiveTurns(this.store, sessionId, []),
        pending_approvals: this.store.getPendingApprovals(sessionId),
        changed_turn_ids: [],
        needs_full_sync: true,
        fallback_reason: 'changed events did not include turn ids',
      };
    }

    try {
      const { entries, changedTurnIds, missingFinalAgentTurnIds } = await this.turnContent.buildContentEntriesForTurns(sessionId, turnIds);
      const fallbackTurnIds = new Set(missingFinalAgentTurnIds);
      for (const turnId of turnIds) {
        if (this.turnContent.turnRequiresAuthoritativeItems(sessionId, turnId) && !changedTurnIds.includes(turnId)) {
          fallbackTurnIds.add(turnId);
        }
      }
      if (fallbackTurnIds.size > 0) {
        this.#logSync('session.sync.build.needs_full_sync', {
          session_id: sessionId,
          since_seq: sinceSeq,
          last_seq: refreshedLastSeq,
          requested_turn_ids: turnIds,
          changed_turn_ids: changedTurnIds,
          missing_final_agent_turn_ids: missingFinalAgentTurnIds,
          fallback_turn_ids: Array.from(fallbackTurnIds),
          entries: entries.length,
          item_counts: syncItemTypeCounts(entries),
          entry_tail: summarizeSyncEntries(entries),
        });
        return {
          session_id: sessionId,
          thread_id: session.thread_id ?? sessionId,
          last_seq: refreshedLastSeq,
          entries: [],
          active_turns: buildActiveTurns(this.store, sessionId, []),
          pending_approvals: this.store.getPendingApprovals(sessionId),
          changed_turn_ids: changedTurnIds,
          needs_full_sync: true,
          fallback_reason: `missing final agentMessage for turns: ${Array.from(fallbackTurnIds).join(',')}`,
        };
      }

      this.#logSync('session.sync.build.success', {
        session_id: sessionId,
        since_seq: sinceSeq,
        last_seq: refreshedLastSeq,
        requested_turn_ids: turnIds,
        changed_turn_ids: changedTurnIds,
        entries: entries.length,
        item_counts: syncItemTypeCounts(entries),
        entry_tail: summarizeSyncEntries(entries),
      });
      return {
        session_id: sessionId,
        thread_id: session.thread_id ?? sessionId,
        last_seq: refreshedLastSeq,
        entries,
        active_turns: buildActiveTurns(this.store, sessionId, entries),
        pending_approvals: this.store.getPendingApprovals(sessionId),
        changed_turn_ids: changedTurnIds,
        needs_full_sync: false,
        fallback_reason: '',
      };
    } catch (error) {
      this.#logSync('session.sync.build.error', {
        session_id: sessionId,
        since_seq: sinceSeq,
        last_seq: refreshedLastSeq,
        requested_turn_ids: turnIds,
        message: error?.message ?? String(error),
      });
      return {
        session_id: sessionId,
        thread_id: session.thread_id ?? sessionId,
        last_seq: refreshedLastSeq,
        entries: [],
        active_turns: buildActiveTurns(this.store, sessionId, []),
        pending_approvals: this.store.getPendingApprovals(sessionId),
        changed_turn_ids: turnIds,
        needs_full_sync: true,
        fallback_reason: error?.message ?? 'session sync failed',
      };
    }
  }


  async #loadHydrationThread(sessionId, session, options = {}) {
    const thread = session.thread ?? null;
    const rawThread = thread?.thread ?? thread;
    const status = getThreadStatusType(thread);
    const inlineTurns = Array.isArray(rawThread?.turns) ? rawThread.turns : [];
    const hasMeaningfulEvents = hasMeaningfulSessionEvents(session);
    const shouldSkipHydrationRead = !options.forceRead
      && thread
      && status !== 'notLoaded'
      && inlineTurns.length === 0
      && !hasMeaningfulEvents;

    if (shouldSkipHydrationRead) {
      return thread;
    }

    if (typeof this.backend.readThread === 'function') {
      try {
        const read = await this.backend.readThread(session.thread_id ?? sessionId, {
          includeTurns: true,
        });
        const thread = read?.thread ?? read ?? null;
        if (thread) {
          const normalized = normalizeThreadSummary(thread, backendName(this.backend));
          if (normalized) {
            await this.store.upsertSession(mergeSessionSummary(normalized, this.store.getSession(sessionId)));
          }
          return thread;
        }
      } catch {}
    }

    if (thread && status !== 'notLoaded') {
      return thread;
    }

    const resumed = await this.resumeStoredSession(sessionId, {
      model: session.model ?? null,
      cwd: session.cwd ?? null,
      excludeTurns: false,
    });
    return resumed?.resumed?.thread ?? resumed?.session?.thread ?? thread ?? null;
  }

  async #hydrateSessionHistory(sessionId, options = {}) {
    const session = this.store.getSession(sessionId);
    if (!session) return [];

    const rolloutAfterBytes = Number(options.rolloutAfterBytes);
    if (Number.isFinite(rolloutAfterBytes) && rolloutAfterBytes >= 0 && session.thread?.path) {
      return buildEventsFromRolloutFile(
        sessionId,
        session.thread.path,
        session.thread_id ?? sessionId,
        { afterBytes: rolloutAfterBytes },
      );
    }

    try {
      const thread = await this.#loadHydrationThread(sessionId, session, options);
      const hydratedEvents = await this.turnContent.buildEventsFromThread(sessionId, thread);
      if (hydratedEvents.length > 0) {
        return hydratedEvents;
      }

      if (session.thread?.path) {
        const fromRollout = await buildEventsFromRolloutFile(sessionId, session.thread.path, session.thread_id ?? sessionId);
        if (fromRollout.length > 0) {
          return fromRollout;
        }
      }

      return clone(session.events ?? []);
    } catch {
      return clone(session.events ?? []);
    }
  }
}
