import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { nowIso } from './protocol.mjs';

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function deepFreeze(value) {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const key of Object.keys(value)) {
      deepFreeze(value[key]);
    }
  }
  return value;
}

export class StateStore {
  constructor(filePath, { maxEventsPerSession = 500, saveDebounceMs = 200 } = {}) {
    this.filePath = filePath;
    this.maxEventsPerSession = maxEventsPerSession;
    this.saveDebounceMs = saveDebounceMs;
    this.state = this.#emptyState();
    this.saveTimer = null;
    this.saveInFlight = null;
    this.savePending = false;
    // Sessions are read far more often than they are written, so cloned read
    // snapshots are cached until the next mutation instead of being rebuilt on
    // every call.
    this.revision = 0;
    this.snapshot = null;
  }

  #invalidate() {
    this.revision += 1;
    this.snapshot = null;
  }

  #emptyState() {
    return {
      version: 1,
      sessions: {},
      devices: {},
    };
  }

  async load() {
    // Pending debounced mutations must reach disk before re-reading, otherwise
    // an in-memory session created before start() is silently dropped.
    await this.flush();
    this.#invalidate();
    await fs.mkdir(path.dirname(this.filePath), { recursive: true });
    try {
      const raw = await fs.readFile(this.filePath, 'utf8');
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object') {
        this.state = {
          ...this.#emptyState(),
          sessions: parsed.sessions ?? {},
          devices: parsed.devices ?? {},
        };
      }
    } catch (error) {
      if (error?.code !== 'ENOENT') throw error;
      await this.save();
    }
  }

  async clear() {
    this.state = this.#emptyState();
    this.#invalidate();
    await this.save();
  }

  async save() {
    await fs.mkdir(path.dirname(this.filePath), { recursive: true });
    const tmp = `${this.filePath}.${randomUUID()}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(this.state, null, 2), { encoding: 'utf8', mode: 0o600 });
    await fs.rename(tmp, this.filePath);
  }

  // State is serialized as one large JSON document. Writing it on every event
  // blocks the event loop and makes session.list take seconds, so mutations
  // only mark the store dirty and coalesce writes into one flush.
  saveSoon() {
    this.savePending = true;
    this.#invalidate();
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.flush().catch((error) => console.error('state save failed:', error));
    }, this.saveDebounceMs);
    this.saveTimer.unref?.();
  }

  async flush() {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    if (this.saveInFlight) {
      await this.saveInFlight;
    }
    if (!this.savePending) return;
    this.savePending = false;
    this.saveInFlight = this.save().finally(() => {
      this.saveInFlight = null;
    });
    await this.saveInFlight;
    if (this.savePending) {
      await this.flush();
    }
  }

  listSessions() {
    return Object.keys(this.state.sessions)
      .map((sessionId) => this.getSession(sessionId))
      .filter(Boolean)
      .sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  }

  // Read-only traversal of the live sessions, without the clone and freeze that
  // getSession() performs. For callers that only scan for data (the uploads
  // sweep); the values must not be mutated.
  forEachSession(visitor) {
    for (const session of Object.values(this.state.sessions)) {
      visitor(session);
    }
  }

  getSession(sessionId) {
    const session = this.state.sessions[sessionId];
    if (!session) return null;
    const cached = this.snapshot;
    if (cached && cached.sessionId === sessionId && cached.revision === this.revision) {
      return cached.value;
    }
    const value = deepFreeze(clone(session));
    this.snapshot = { sessionId, revision: this.revision, value };
    return value;
  }

  getLastSeq(sessionId) {
    return this.state.sessions[sessionId]?.lastSeq ?? 0;
  }

  getPendingApprovals(sessionId) {
    const approvals = Object.values(this.state.sessions[sessionId]?.pending_approvals ?? {});
    return clone(approvals).sort((a, b) => String(a.at ?? '').localeCompare(String(b.at ?? '')));
  }

  getPendingApproval(sessionId, requestId) {
    if (!sessionId) return null;
    const approval = this.state.sessions[sessionId]?.pending_approvals?.[String(requestId)];
    return approval ? clone(approval) : null;
  }

  #applyUpsert(session, { touch = false } = {}) {
    const now = nowIso();
    const current = this.state.sessions[session.session_id] ?? {
      session_id: session.session_id,
      thread_id: session.thread_id ?? session.session_id,
      title: session.title ?? '',
      cwd: session.cwd ?? '',
      model: session.model ?? '',
      backend: session.backend ?? 'mock',
      preview: session.preview ?? '',
      active: true,
      createdAt: session.createdAt ?? now,
      updatedAt: session.updatedAt ?? now,
      lastSeq: 0,
      thread: null,
      events: [],
      pending_approvals: {},
    };
    const next = {
      ...current,
      ...clone(session),
      session_id: session.session_id,
      thread_id: session.thread_id ?? current.thread_id,
      thread: session.thread ?? current.thread,
      events: current.events ?? [],
      pending_approvals: current.pending_approvals ?? {},
      updatedAt: touch ? now : (session.updatedAt ?? current.updatedAt ?? now),
    };
    this.state.sessions[next.session_id] = next;
    return next;
  }

  async upsertSession(session, { touch = false } = {}) {
    const next = this.#applyUpsert(session, { touch });
    this.saveSoon();
    return clone(next);
  }

  async upsertSessions(sessions) {
    const applied = [];
    for (const session of sessions) {
      applied.push(clone(this.#applyUpsert(session)));
    }
    if (applied.length > 0) {
      this.saveSoon();
    }
    return applied;
  }

  async appendEvent(sessionId, event) {
    const session = this.state.sessions[sessionId];
    if (!session) {
      throw new Error(`unknown session: ${sessionId}`);
    }
    const stored = {
      ...clone(event),
      seq: (session.lastSeq ?? 0) + 1,
      at: event.at ?? nowIso(),
    };
    session.lastSeq = stored.seq;
    session.updatedAt = stored.at;
    session.events = session.events ?? [];
    session.events.push(stored);
    if (session.events.length > this.maxEventsPerSession) {
      session.events = session.events.slice(-this.maxEventsPerSession);
    }
    this.state.sessions[sessionId] = session;
    this.saveSoon();
    return clone(stored);
  }

  async attachTurnIdToRequestEvent(sessionId, requestId, turnId) {
    const session = this.state.sessions[sessionId];
    if (!session || !requestId || !turnId) return null;
    const events = session.events ?? [];
    for (let index = events.length - 1; index >= 0; index -= 1) {
      const event = events[index];
      if (event?.event !== 'turn/input' || event?.request_id !== requestId) {
        continue;
      }
      if (event.turn_id === turnId) {
        return clone(event);
      }
      events[index] = {
        ...event,
        turn_id: turnId,
      };
      session.updatedAt = nowIso();
      this.state.sessions[sessionId] = session;
      this.saveSoon();
      return clone(events[index]);
    }
    return null;
  }

  async recordPendingApproval(sessionId, approval) {
    const session = this.state.sessions[sessionId];
    if (!session) {
      throw new Error(`unknown session: ${sessionId}`);
    }
    session.pending_approvals = session.pending_approvals ?? {};
    session.pending_approvals[String(approval.request_id)] = clone(approval);
    session.updatedAt = nowIso();
    this.saveSoon();
    return clone(approval);
  }

  async resolveApproval(sessionId, requestId) {
    const session = this.state.sessions[sessionId];
    if (!session) {
      throw new Error(`unknown session: ${sessionId}`);
    }
    const key = String(requestId);
    if (session.pending_approvals?.[key]) {
      delete session.pending_approvals[key];
      session.updatedAt = nowIso();
      this.saveSoon();
    }
  }

  syncEvents(sessionId, sinceSeq = 0) {
    const session = this.state.sessions[sessionId];
    if (!session) return [];
    return clone((session.events ?? []).filter((event) => (event.seq ?? 0) > sinceSeq));
  }
}
