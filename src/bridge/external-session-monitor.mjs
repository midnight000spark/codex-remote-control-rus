import fs from 'node:fs/promises';
import { backendName } from './common.mjs';
import {
  mergeSessionSummary,
  normalizeThreadSummary,
} from './session-normalization.mjs';
import {
  createRolloutEventCursor,
  readRolloutEventsFromCursor,
} from './rollout-history.mjs';

function rolloutPath(session) {
  const value = session?.thread?.path;
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function rawThreadStatus(thread) {
  const value = thread?.status;
  return typeof value === 'string' ? value : value?.type ?? value?.status ?? '';
}

function turnFingerprint(thread) {
  const turns = Array.isArray(thread?.turns) ? thread.turns : [];
  return [
    turns.length,
    ...turns.slice(-3).map((turn) => [
      turn?.id ?? '',
      rawThreadStatus(turn),
      turn?.startedAt ?? turn?.createdAt ?? '',
      turn?.completedAt ?? turn?.updatedAt ?? '',
      Array.isArray(turn?.items) ? turn.items.length : null,
    ]),
  ];
}

async function buildSnapshot(session) {
  const filePath = rolloutPath(session);
  let rolloutSize = null;
  let rolloutMtimeMs = null;
  if (filePath) {
    try {
      const stat = await fs.stat(filePath);
      rolloutSize = stat.size;
      rolloutMtimeMs = stat.mtimeMs;
    } catch {}
  }

  const thread = session?.thread ?? {};
  return {
    rolloutPath: filePath,
    rolloutSize,
    rolloutCursor: null,
    fingerprint: JSON.stringify([
      session?.updatedAt ?? '',
      session?.active ?? true,
      session?.preview ?? '',
      rawThreadStatus(thread),
      turnFingerprint(thread),
      filePath,
      rolloutSize,
      rolloutMtimeMs,
    ]),
  };
}

function mergeMonitorSummary(summary, stored) {
  const merged = mergeSessionSummary(summary, stored);
  const summaryTurns = Array.isArray(summary?.thread?.turns) ? summary.thread.turns : [];
  const storedTurns = Array.isArray(stored?.thread?.turns) ? stored.thread.turns : [];
  if (summaryTurns.length === 0 && storedTurns.length > 0) {
    merged.thread = {
      ...stored.thread,
      ...summary.thread,
      turns: stored.thread.turns,
    };
  }
  return merged;
}

// A bridge without a reachable Codex backend used to retry every 5s forever,
// which keeps the CPU and the log busy. Each consecutive failure doubles the
// delay up to a cap; a success puts it back to the poll interval.
export function externalMonitorBackoffMs(intervalMs, failures, maxBackoffMs) {
  const base = Math.max(20, Number(intervalMs) || 5000);
  if (!(failures > 0)) return base;
  const cap = Math.max(base, Number(maxBackoffMs) || 60_000);
  return Math.min(base * 2 ** Math.min(failures, 4), cap);
}

export class ExternalSessionMonitor {
  constructor({
    backend,
    store,
    sessionContent,
    intervalMs = 5000,
    maxBackoffMs = 60_000,
    limit = 50,
    onEvents = () => {},
    logSync = () => {},
  }) {
    this.backend = backend;
    this.store = store;
    this.sessionContent = sessionContent;
    this.intervalMs = Math.max(20, Number(intervalMs) || 5000);
    this.maxBackoffMs = Math.max(this.intervalMs, Number(maxBackoffMs) || 60_000);
    this.failureCount = 0;
    this.limit = Math.max(1, Number(limit) || 50);
    this.onEvents = onEvents;
    this.logSync = logSync;
    this.snapshots = new Map();
    this.initialized = false;
    this.running = false;
    this.timer = null;
  }

  start() {
    if (this.running || typeof this.backend.listThreads !== 'function') return;
    this.running = true;
    this.logSync('external_monitor.started', {
      interval_ms: this.intervalMs,
      limit: this.limit,
      initialized: this.initialized,
    });
    this.#schedule(0);
  }

  stop() {
    if (!this.running) return;
    this.running = false;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.logSync('external_monitor.stopped', {
      tracked_sessions: this.snapshots.size,
    });
  }

  #schedule(delayMs) {
    if (!this.running) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.#poll()
        .then(() => {
          this.failureCount = 0;
        })
        .catch((error) => {
          this.failureCount += 1;
          this.logSync('external_monitor.poll.error', {
            message: error?.message ?? String(error),
            failures: this.failureCount,
          });
        })
        .finally(() => this.#schedule(this.#nextDelayMs()));
    }, delayMs);
    this.timer.unref?.();
  }

  #nextDelayMs() {
    return externalMonitorBackoffMs(this.intervalMs, this.failureCount, this.maxBackoffMs);
  }

  async #poll() {
    const listed = await this.backend.listThreads({ limit: this.limit });
    const threads = Array.isArray(listed) ? listed : listed?.data ?? [];
    const sessions = threads
      .map((thread) => normalizeThreadSummary(thread, backendName(this.backend)))
      .filter((session) => session?.session_id);

    if (!this.initialized) {
      for (const session of sessions) {
        const stored = this.store.getSession(session.session_id);
        if (!stored) {
          await this.store.upsertSession(session);
        }
        this.snapshots.set(session.session_id, await buildSnapshot(session));
      }
      this.initialized = true;
      this.logSync('external_monitor.baseline', {
        sessions: sessions.length,
      });
      return;
    }

    for (const summary of sessions) {
      const sessionId = summary.session_id;
      const previous = this.snapshots.get(sessionId) ?? null;
      const current = await buildSnapshot(summary);
      if (previous?.rolloutPath === current.rolloutPath) {
        current.rolloutCursor = previous.rolloutCursor ?? null;
      }
      if (previous?.fingerprint === current.fingerprint) continue;

      const stored = this.store.getSession(sessionId);
      await this.store.upsertSession(mergeMonitorSummary(summary, stored));
      const sinceSeq = this.store.getLastSeq(sessionId);
      const changedRollout = previous?.rolloutPath
        && previous.rolloutPath === current.rolloutPath
        && Number.isFinite(previous.rolloutSize)
        && Number.isFinite(current.rolloutSize)
        && previous.rolloutSize !== current.rolloutSize;
      const rolloutAfterBytes = changedRollout
        ? (current.rolloutSize >= previous.rolloutSize ? previous.rolloutSize : 0)
        : null;
      let hydratedEvents = null;
      if (changedRollout) {
        const cursor = current.rolloutSize < previous.rolloutSize
          ? await createRolloutEventCursor(current.rolloutPath, 0)
          : previous.rolloutCursor
            ?? await createRolloutEventCursor(previous.rolloutPath, previous.rolloutSize);
        const incremental = await readRolloutEventsFromCursor(
          sessionId,
          current.rolloutPath,
          summary.thread_id ?? sessionId,
          cursor,
          current.rolloutSize,
        );
        current.rolloutCursor = incremental.cursor;
        hydratedEvents = incremental.events;
      }
      const refresh = await this.sessionContent.refreshSessionHistory(sessionId, sinceSeq, {
        ...(hydratedEvents != null ? { hydratedEvents } : {}),
        forceRead: true,
        includeTailCompletion: true,
      });
      if (!refresh.ok) continue;

      this.snapshots.set(sessionId, current);
      if (refresh.events.length > 0) {
        await this.onEvents(sessionId, refresh.events);
      }
      this.logSync('external_monitor.session_changed', {
        session_id: sessionId,
        new_session: previous == null,
        rollout_after_bytes: rolloutAfterBytes,
        appended: refresh.appended,
        skipped: refresh.skipped,
        last_seq: refresh.lastSeq,
      });
    }
  }
}
