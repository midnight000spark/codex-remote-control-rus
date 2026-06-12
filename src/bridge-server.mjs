import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { WebSocket, WebSocketServer } from 'ws';
import { createError, createResponse, createEvent, nowIso } from './protocol.mjs';
import {
  backendName,
  getTurnInputItemId,
  requestKey,
  safeJson,
  summarizeApprovalPayload,
  summarizeClientPayload,
} from './bridge/common.mjs';
import { readSessionFile, clampMaxBytes } from './bridge/file-reader.mjs';
import { SessionContentController } from './bridge/session-content-controller.mjs';
import {
  defaultSandboxPolicy,
  isThreadNotFoundError,
  mergeSessionSummary,
  normalizePermissionSelection,
  normalizeSandboxPolicy,
  normalizeThreadSummary,
} from './bridge/session-normalization.mjs';
import {
  normalizeSeq,
  nowLocalIso,
  summarizeSyncEntries,
  syncItemTypeCounts,
} from './bridge/sync-log.mjs';

export class BridgeServer extends EventEmitter {
  constructor({
    backend,
    store,
    host = '127.0.0.1',
    port = 8787,
    token = null,
    bridgeId = null,
    name = 'codex-remote-control',
    version = '0.1.0',
    syncLogPath = null,
  }) {
    super();
    this.backend = backend;
    this.store = store;
    this.host = host;
    this.port = port;
    this.token = token;
    this.bridgeId = typeof bridgeId === 'string' && bridgeId.trim() ? bridgeId.trim() : randomUUID();
    this.name = name;
    this.version = version;
    this.syncLogPath = syncLogPath;
    this.httpServer = null;
    this.wsServer = null;
    this.connections = new Set();
    this.backendListener = (message) => this.#handleBackendMessage(message);
    this.sessionContent = new SessionContentController({
      backend: this.backend,
      store: this.store,
      logSync: (event, details) => this.#logSync(event, details),
    });
  }

  #logSync(event, details = {}) {
    if (!this.syncLogPath) return;
    const line = JSON.stringify({
      at: nowLocalIso(),
      event,
      ...details,
    });
    fs.mkdir(path.dirname(this.syncLogPath), { recursive: true })
      .then(() => fs.appendFile(this.syncLogPath, `${line}\n`, 'utf8'))
      .catch(() => {});
  }

  #logApproval(event, details = {}) {
    this.#logSync(`approval.${event}`, details);
  }

  async start() {
    await this.store.load();
    await this.backend.start();
    this.backend.on('message', this.backendListener);

    this.httpServer = http.createServer((req, res) => this.#handleHttp(req, res));
    this.wsServer = new WebSocketServer({ server: this.httpServer });
    this.wsServer.on('connection', (ws, req) => this.#handleConnection(ws, req));

    await new Promise((resolve) => {
      this.httpServer.listen(this.port, this.host, resolve);
    });

    return this;
  }

  async stop() {
    this.backend.off('message', this.backendListener);
    for (const ws of this.connections) {
      try {
        ws.close(1001, 'server shutting down');
      } catch {}
    }
    this.connections.clear();
    if (this.wsServer) {
      await new Promise((resolve) => this.wsServer.close(resolve));
    }
    if (this.httpServer) {
      await new Promise((resolve) => this.httpServer.close(resolve));
    }
    await this.backend.stop();
  }

  address() {
    const addr = this.httpServer?.address();
    if (typeof addr === 'object' && addr) {
      return { host: addr.address, port: addr.port };
    }
    return { host: this.host, port: this.port };
  }

  #handleHttp(req, res) {
    if (req.url === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, name: this.name, version: this.version }));
      return;
    }
    if (req.url === '/v1/sessions' && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ sessions: this.store.listSessions() }));
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: false, error: 'not_found' }));
  }

  async #handleConnection(ws, req) {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    const providedToken = url.searchParams.get('token') ?? req.headers['x-bridge-token'];
    if (this.token && providedToken !== this.token) {
      this.#logSync('ws.unauthorized', {
        remote: req.socket?.remoteAddress ?? '',
        url: url.pathname,
        has_token: Boolean(providedToken),
      });
      ws.close(1008, 'unauthorized');
      return;
    }

    this.connections.add(ws);
    this.#logSync('ws.connected', {
      remote: req.socket?.remoteAddress ?? '',
      url: req.url ?? '',
      connections: this.connections.size,
    });
    ws.send(JSON.stringify({
      type: 'hello',
      ok: true,
      payload: {
        name: this.name,
        version: this.version,
        bridge_id: this.bridgeId,
        server_time: nowIso(),
      },
    }));

    ws.on('message', async (buffer) => {
      const raw = buffer.toString('utf8');
      const parsed = safeJson(raw);
      if (!parsed.ok) {
        this.#logSync('ws.message.bad_json', {
          remote: req.socket?.remoteAddress ?? '',
          bytes: Buffer.byteLength(raw),
        });
        ws.send(JSON.stringify(createError(null, 'bad_json', 'invalid JSON', { raw })));
        return;
      }
      try {
        this.#logSync('ws.message', {
          remote: req.socket?.remoteAddress ?? '',
          request_id: parsed.value?.id ?? null,
          type: parsed.value?.type ?? '',
          payload: summarizeClientPayload(parsed.value?.type ?? '', parsed.value?.payload),
        });
        await this.#handleClientMessage(ws, parsed.value);
      } catch (error) {
        this.#logSync('ws.message.error', {
          remote: req.socket?.remoteAddress ?? '',
          request_id: parsed.value?.id ?? null,
          type: parsed.value?.type ?? '',
          error: error?.message ?? String(error),
        });
        ws.send(JSON.stringify(createError(parsed.value?.id ?? null, 'internal_error', error?.message ?? 'internal error')));
      }
    });

    ws.on('close', () => {
      this.connections.delete(ws);
      this.#logSync('ws.closed', {
        remote: req.socket?.remoteAddress ?? '',
        connections: this.connections.size,
      });
    });
  }

  async #handleClientMessage(ws, message) {
    const type = message?.type;
    const id = message?.id ?? randomUUID();
    const payload = message?.payload ?? {};

    switch (type) {
      case 'ping':
        ws.send(JSON.stringify(createResponse(id, { pong: true }, 'pong')));
        return;
      case 'session.list': {
        const backendThreads = await this.backend.listThreads(payload);
        const sessionsById = new Map();
        const backendSessions = backendThreads.map((thread) => {
          const summary = normalizeThreadSummary(thread, backendName(this.backend));
          const stored = summary?.session_id ? this.store.getSession(summary.session_id) : null;
          return mergeSessionSummary(summary, stored);
        }).filter((session) => session?.session_id);
        for (const session of backendSessions) {
          await this.store.upsertSession(session);
          sessionsById.set(session.session_id, session);
        }
        for (const session of this.store.listSessions()) {
          if (!sessionsById.has(session.session_id)) {
            sessionsById.set(session.session_id, session);
          }
        }
        const sessions = [...sessionsById.values()]
          .sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')));
        ws.send(JSON.stringify(createResponse(id, { sessions })));
        return;
      }
      case 'model.list': {
        const listed = typeof this.backend.listModels === 'function'
          ? await this.backend.listModels(payload)
          : { data: [], nextCursor: null };
        const data = Array.isArray(listed) ? listed : listed?.data ?? [];
        const nextCursor = Array.isArray(listed) ? null : listed?.nextCursor ?? null;
        ws.send(JSON.stringify(createResponse(id, { data, nextCursor })));
        return;
      }
      case 'collaboration_mode.list': {
        const listed = typeof this.backend.listCollaborationModes === 'function'
          ? await this.backend.listCollaborationModes(payload)
          : { data: [] };
        const data = Array.isArray(listed) ? listed : listed?.data ?? [];
        ws.send(JSON.stringify(createResponse(id, { data })));
        return;
      }
      case 'session.start': {
        const started = await this.backend.startThread({
          cwd: payload.cwd ?? null,
          model: payload.model ?? null,
          approvalPolicy: payload.approvalPolicy ?? null,
          approvalsReviewer: payload.approvalsReviewer ?? null,
          sandbox: payload.sandbox ?? null,
          permissions: normalizePermissionSelection(payload.permissions),
          config: payload.config ?? null,
          serviceName: payload.serviceName ?? null,
          baseInstructions: payload.baseInstructions ?? null,
          developerInstructions: payload.developerInstructions ?? null,
          personality: payload.personality ?? null,
          ephemeral: payload.ephemeral ?? null,
          sessionStartSource: payload.sessionStartSource ?? null,
          threadSource: payload.threadSource ?? null,
          environments: payload.environments ?? null,
          dynamicTools: payload.dynamicTools ?? null,
          mockExperimentalField: payload.mockExperimentalField ?? null,
          experimentalRawEvents: payload.experimentalRawEvents ?? true,
          persistExtendedHistory: payload.persistExtendedHistory ?? false,
        });
        const session = normalizeThreadSummary(started, backendName(this.backend));
        await this.store.upsertSession(session, { touch: true });
        ws.send(JSON.stringify(createResponse(id, { session })));
        return;
      }
      case 'session.resume': {
        const resumed = await this.backend.resumeThread(payload.session_id ?? payload.thread_id, {
          threadId: payload.thread_id ?? payload.session_id,
          history: payload.history ?? null,
          path: payload.path ?? null,
          model: payload.model ?? null,
          modelProvider: payload.modelProvider ?? null,
          serviceTier: payload.serviceTier ?? null,
          cwd: payload.cwd ?? null,
          approvalPolicy: payload.approvalPolicy ?? null,
          approvalsReviewer: payload.approvalsReviewer ?? null,
          sandbox: payload.sandbox ?? null,
          permissions: normalizePermissionSelection(payload.permissions),
          config: payload.config ?? null,
          baseInstructions: payload.baseInstructions ?? null,
          developerInstructions: payload.developerInstructions ?? null,
          personality: payload.personality ?? null,
          excludeTurns: payload.excludeTurns ?? null,
          persistExtendedHistory: payload.persistExtendedHistory ?? false,
        });
        const session = normalizeThreadSummary(resumed, backendName(this.backend));
        await this.store.upsertSession(session);
        ws.send(JSON.stringify(createResponse(id, { session })));
        return;
      }
      case 'session.update': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const current = this.store.getSession(sessionId);
        if (!current) {
          ws.send(JSON.stringify(createError(id, 'not_found', `unknown session: ${sessionId}`)));
          return;
        }
        const patch = {
          session_id: current.session_id,
        };
        if (Object.prototype.hasOwnProperty.call(payload, 'approvalPolicy')) {
          patch.approvalPolicy = payload.approvalPolicy;
        }
        if (Object.prototype.hasOwnProperty.call(payload, 'permissions')) {
          patch.permissions = normalizePermissionSelection(payload.permissions);
        }
        if (Object.prototype.hasOwnProperty.call(payload, 'sandbox') || Object.prototype.hasOwnProperty.call(payload, 'sandboxPolicy')) {
          const normalizedSandbox = normalizeSandboxPolicy(
            Object.prototype.hasOwnProperty.call(payload, 'sandbox') ? payload.sandbox : payload.sandboxPolicy,
          );
          if (normalizedSandbox == null) {
            ws.send(JSON.stringify(createError(id, 'invalid_request', 'invalid sandbox')));
            return;
          }
          patch.sandbox = normalizedSandbox;
          patch.sandboxOverride = true;
        }
        const session = await this.store.upsertSession(patch, { touch: true });
        this.#emitSessionChanged(sessionId);
        ws.send(JSON.stringify(createResponse(id, { session })));
        return;
      }
      case 'turn.send': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const inputItemId = getTurnInputItemId({ payload, requestId: id, sessionId });
        const inputEvent = createEvent('turn/input', {
          session_id: sessionId,
          thread_id: sessionId,
          request_id: id,
          payload: {
            itemId: inputItemId,
            item_id: inputItemId,
            text: payload.text ?? '',
            input: payload.input ?? null,
          },
        });
        let session = this.store.getSession(sessionId);
        if (!session) {
          await this.store.upsertSession({
            session_id: sessionId,
            thread_id: sessionId,
            title: payload.title ?? '',
            cwd: payload.cwd ?? '',
            model: payload.model ?? '',
            backend: backendName(this.backend),
            preview: payload.text ?? '',
            active: true,
          });
          session = this.store.getSession(sessionId);
        }
        const storedInputEvent = await this.store.appendEvent(sessionId, inputEvent);
        this.#broadcast(storedInputEvent);
        const requestedPermissions = normalizePermissionSelection(payload.permissions);
        const requestedSandboxPolicy = Object.prototype.hasOwnProperty.call(payload, 'sandboxPolicy')
          ? normalizeSandboxPolicy(payload.sandboxPolicy)
          : normalizeSandboxPolicy(session?.sandbox ?? null) ?? defaultSandboxPolicy();
        const turnParams = {
          text: payload.text ?? '',
          input: payload.input ?? null,
          responsesapiClientMetadata: payload.responsesapiClientMetadata ?? null,
          environments: payload.environments ?? null,
          cwd: payload.cwd ?? null,
          approvalPolicy: payload.approvalPolicy ?? null,
          approvalsReviewer: payload.approvalsReviewer ?? null,
          sandboxPolicy: requestedSandboxPolicy,
          permissions: requestedPermissions,
          model: payload.model ?? null,
          serviceTier: payload.serviceTier ?? null,
          effort: payload.effort ?? null,
          summary: payload.summary ?? null,
          personality: payload.personality ?? null,
          outputSchema: payload.outputSchema ?? null,
          collaborationMode: payload.collaborationMode ?? null,
          turnId: payload.turn_id ?? null,
        };
        let ensured = await this.sessionContent.ensureSessionLoaded(sessionId, {
          model: turnParams.model,
          cwd: turnParams.cwd,
          approvalPolicy: turnParams.approvalPolicy,
          approvalsReviewer: turnParams.approvalsReviewer,
          sandbox: requestedSandboxPolicy,
          permissions: turnParams.permissions,
          personality: turnParams.personality,
          excludeTurns: true,
        });
        let targetThreadId = ensured?.session?.thread_id ?? session?.thread_id ?? sessionId;
        let turn;
        try {
          turn = await this.backend.startTurn(targetThreadId, turnParams);
        } catch (error) {
          if (!isThreadNotFoundError(error)) throw error;
          ensured = await this.sessionContent.resumeStoredSession(sessionId, {
            model: turnParams.model,
            cwd: turnParams.cwd,
            approvalPolicy: turnParams.approvalPolicy,
            approvalsReviewer: turnParams.approvalsReviewer,
            sandbox: requestedSandboxPolicy,
            permissions: turnParams.permissions,
            personality: turnParams.personality,
            excludeTurns: true,
          });
          targetThreadId = ensured?.session?.thread_id ?? targetThreadId;
          turn = await this.backend.startTurn(targetThreadId, turnParams);
        }
        if (turn?.id) {
          const attachedInputEvent = await this.store.attachTurnIdToRequestEvent(sessionId, id, turn.id);
          if (attachedInputEvent) {
            const { seq, at, ...boundInputEvent } = attachedInputEvent;
            const storedBoundInputEvent = await this.store.appendEvent(sessionId, {
              ...boundInputEvent,
              replaces_seq: seq ?? null,
            });
            this.#broadcast(storedBoundInputEvent);
          }
        }
        this.#emitSessionChanged(sessionId);
        ws.send(JSON.stringify(createResponse(id, { turn, session_id: sessionId })));
        return;
      }
      case 'turn.steer': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const turnId = payload.turn_id ?? payload.turnId ?? payload.expectedTurnId;
        if (!sessionId || !turnId) {
          ws.send(JSON.stringify(createError(id, 'invalid_request', 'session_id and turn_id are required')));
          return;
        }

        const session = this.store.getSession(sessionId);
        if (!session) {
          ws.send(JSON.stringify(createError(id, 'not_found', `unknown session: ${sessionId}`)));
          return;
        }
        const targetThreadId = payload.thread_id ?? session?.thread_id ?? sessionId;
        const inputItemId = getTurnInputItemId({ payload, requestId: id, turnId, sessionId });
        const inputEvent = createEvent('turn/input', {
          session_id: sessionId,
          thread_id: targetThreadId,
          turn_id: turnId,
          request_id: id,
          payload: {
            itemId: inputItemId,
            item_id: inputItemId,
            text: payload.text ?? '',
            input: payload.input ?? null,
          },
        });
        const storedInputEvent = await this.store.appendEvent(sessionId, inputEvent);
        this.#broadcast(storedInputEvent);

        const result = await this.backend.steerTurn(targetThreadId, turnId, {
          text: payload.text ?? '',
          input: payload.input ?? null,
          responsesapiClientMetadata: payload.responsesapiClientMetadata ?? null,
        });
        this.#emitSessionChanged(sessionId);
        ws.send(JSON.stringify(createResponse(id, {
          turn_id: result?.turnId ?? result?.turn_id ?? turnId,
          session_id: sessionId,
        })));
        return;
      }
      case 'turn.interrupt': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        await this.backend.interruptTurn(
          payload.thread_id ?? session?.thread_id ?? sessionId,
          payload.turn_id ?? payload.turnId,
        );
        ws.send(JSON.stringify(createResponse(id, { ok: true })));
        return;
      }
      case 'goal.get': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        const threadId = payload.thread_id ?? session?.thread_id ?? sessionId;
        if (!threadId) {
          ws.send(JSON.stringify(createError(id, 'invalid_request', 'session_id or thread_id is required')));
          return;
        }
        const result = typeof this.backend.getThreadGoal === 'function'
          ? await this.backend.getThreadGoal(threadId)
          : { goal: null };
        ws.send(JSON.stringify(createResponse(id, {
          session_id: sessionId,
          thread_id: threadId,
          goal: result?.goal ?? null,
        })));
        return;
      }
      case 'goal.set': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        const threadId = payload.thread_id ?? session?.thread_id ?? sessionId;
        const objective = typeof payload.objective === 'string' ? payload.objective.trim() : '';
        if (!threadId) {
          ws.send(JSON.stringify(createError(id, 'invalid_request', 'session_id or thread_id is required')));
          return;
        }
        if (!objective) {
          ws.send(JSON.stringify(createError(id, 'invalid_request', 'objective is required')));
          return;
        }
        if (typeof this.backend.setThreadGoal !== 'function') {
          ws.send(JSON.stringify(createError(id, 'unsupported', 'thread goal is not supported by this backend')));
          return;
        }
        const tokenBudget = Number.isFinite(Number(payload.tokenBudget)) && Number(payload.tokenBudget) > 0
          ? Number(payload.tokenBudget)
          : null;
        const result = await this.backend.setThreadGoal(threadId, {
          objective,
          status: payload.status ?? 'active',
          tokenBudget,
        });
        ws.send(JSON.stringify(createResponse(id, {
          session_id: sessionId,
          thread_id: threadId,
          goal: result?.goal ?? null,
        })));
        return;
      }
      case 'goal.clear': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        const threadId = payload.thread_id ?? session?.thread_id ?? sessionId;
        if (!threadId) {
          ws.send(JSON.stringify(createError(id, 'invalid_request', 'session_id or thread_id is required')));
          return;
        }
        const result = typeof this.backend.clearThreadGoal === 'function'
          ? await this.backend.clearThreadGoal(threadId)
          : { cleared: false };
        ws.send(JSON.stringify(createResponse(id, {
          session_id: sessionId,
          thread_id: threadId,
          cleared: Boolean(result?.cleared),
        })));
        return;
      }
      case 'approval.response': {
        const clientRequestId = payload.request_id ?? payload.requestId;
        const sessionId = payload.session_id ?? payload.thread_id;
        const pendingApproval = this.store.getPendingApproval(sessionId, clientRequestId);
        const backendRequestId = pendingApproval?.backend_request_id ?? clientRequestId;
        const result = payload.result ?? this.#compileApprovalResult(payload);
        const error = payload.error ?? null;
        this.#logApproval('response.received', {
          request_id: requestKey(clientRequestId),
          backend_request_id: backendRequestId,
          backend_request_id_type: typeof backendRequestId,
          session_id: sessionId,
          request_method: pendingApproval?.request_method ?? '',
          result,
          error,
        });
        await this.backend.respondRequest(
          backendRequestId,
          result,
          error,
        );
        this.#logApproval('response.forwarded', {
          request_id: requestKey(clientRequestId),
          backend_request_id: backendRequestId,
          backend_request_id_type: typeof backendRequestId,
          session_id: sessionId,
        });
        await this.store.resolveApproval(sessionId, clientRequestId);
        this.#emitSessionChanged(sessionId);
        ws.send(JSON.stringify(createResponse(id, { ok: true })));
        return;
      }
      case 'session.sync': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const sinceSeq = normalizeSeq(payload.since_seq);
        this.#logSync('session.sync.request', {
          request_id: id,
          session_id: sessionId,
          since_seq: sinceSeq,
          raw_since_seq: payload.since_seq ?? null,
          incremental: payload.incremental ?? null,
        });
        const sync = await this.sessionContent.buildSessionSync(sessionId, sinceSeq);
        this.#logSync('session.sync.response', {
          request_id: id,
          session_id: sessionId,
          since_seq: sinceSeq,
          last_seq: sync.last_seq ?? null,
          changed_turn_ids: sync.changed_turn_ids ?? [],
          entries: sync.entries?.length ?? 0,
          item_counts: syncItemTypeCounts(sync.entries ?? []),
          active_turns: sync.active_turns?.length ?? 0,
          pending_approvals: sync.pending_approvals?.length ?? 0,
          needs_full_sync: sync.needs_full_sync ?? null,
          fallback_reason: sync.fallback_reason ?? '',
          entry_tail: summarizeSyncEntries(sync.entries ?? []),
        });
        ws.send(JSON.stringify(createResponse(id, sync)));
        return;
      }
      case 'session.content': {
        const sessionId = payload.session_id ?? payload.thread_id;
        this.#logSync('session.content.request', {
          request_id: id,
          session_id: sessionId,
        });
        const content = await this.sessionContent.buildSessionContent(sessionId);
        this.#logSync('session.content.response', {
          request_id: id,
          session_id: sessionId,
          last_seq: content.last_seq ?? null,
          entries: content.entries?.length ?? 0,
          item_counts: syncItemTypeCounts(content.entries ?? []),
          active_turns: content.active_turns?.length ?? 0,
          pending_approvals: content.pending_approvals?.length ?? 0,
          entry_tail: summarizeSyncEntries(content.entries ?? []),
        });
        ws.send(JSON.stringify(createResponse(id, content)));
        return;
      }
      case 'file.read': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const requestedPath = String(payload.path ?? '').trim();
        const maxBytes = clampMaxBytes(payload.max_bytes);
        if (!requestedPath) {
          ws.send(JSON.stringify(createError(id, 'invalid_path', 'path is required')));
          return;
        }
        const result = await readSessionFile(this.store, sessionId, requestedPath, maxBytes);
        ws.send(JSON.stringify(createResponse(id, {
          session_id: sessionId,
          path: requestedPath,
          resolved_path: result.resolvedPath,
          content: result.content,
          truncated: result.truncated,
          bytes: result.bytes,
        })));
        return;
      }
      default:
        ws.send(JSON.stringify(createError(id, 'unknown_message', `unknown message type: ${type}`)));
    }
  }

  async #handleBackendMessage(message) {
    if (message?.type === 'notification') {
      const payload = message.params ?? {};
      const threadId = payload.threadId ?? payload.thread_id ?? payload.thread?.id ?? payload.thread?.threadId ?? payload.thread?.sessionId ?? null;
      const turnId = payload.turnId ?? payload.turn_id ?? payload.turn?.id ?? null;
      const sessionId = threadId ?? payload.sessionId ?? payload.session_id ?? null;
      if (message.method === 'serverRequest/resolved') {
        this.#logApproval('server_request.resolved', {
          request_id: requestKey(payload.requestId ?? payload.request_id),
          thread_id: threadId,
          session_id: sessionId,
          turn_id: turnId,
        });
      }
      const event = createEvent(message.method, {
        session_id: sessionId,
        thread_id: threadId,
        turn_id: turnId,
        payload,
      });
      let broadcastEvent = event;
      if (sessionId) {
        let session = this.store.getSession(sessionId);
        if (session) {
          if (message.method === 'thread/tokenUsage/updated' && payload.tokenUsage) {
            session = await this.store.upsertSession({
              ...session,
              contextWindow: payload.tokenUsage.modelContextWindow ?? session.contextWindow,
              lastTokenUsage: payload.tokenUsage.last ?? session.lastTokenUsage ?? null,
              totalTokenUsage: payload.tokenUsage.total ?? session.totalTokenUsage ?? null,
            });
          }
          broadcastEvent = await this.store.appendEvent(sessionId, event);
        } else {
          await this.store.upsertSession({
            session_id: sessionId,
            thread_id: threadId ?? sessionId,
            title: payload.thread?.name ?? payload.thread?.preview ?? '',
            cwd: payload.thread?.cwd ?? '',
            model: payload.thread?.model ?? '',
            backend: backendName(this.backend),
            preview: payload.thread?.preview ?? '',
            active: true,
            thread: payload.thread ?? null,
          }, { touch: true });
          broadcastEvent = await this.store.appendEvent(sessionId, event);
        }
      }
      this.#broadcast(broadcastEvent);
      if (this.#shouldEmitSessionChanged(message.method, payload)) {
        this.#emitSessionChanged(sessionId);
      }
      return;
    }

    if (message?.type === 'request') {
      const payload = message.params ?? {};
      const threadId = payload.threadId ?? payload.thread_id ?? null;
      const turnId = payload.turnId ?? payload.turn_id ?? null;
      this.#logApproval('request.received', {
        request_id: requestKey(message.id),
        backend_request_id: message.id,
        backend_request_id_type: typeof message.id,
        request_method: message.method,
        thread_id: threadId,
        turn_id: turnId,
        payload: summarizeApprovalPayload(payload),
      });
      const event = createEvent(message.method, {
        session_id: threadId,
        thread_id: threadId,
        turn_id: turnId,
        request_id: requestKey(message.id),
        payload,
      });
      let broadcastEvent = event;
      if (threadId) {
        const session = this.store.getSession(threadId);
        if (!session) {
          await this.store.upsertSession({
            session_id: threadId,
            thread_id: threadId,
            title: payload.thread?.name ?? '',
            cwd: payload.cwd?.path ?? payload.cwd ?? '',
            model: '',
            backend: backendName(this.backend),
            preview: '',
            active: true,
          });
        }
        await this.store.recordPendingApproval(threadId, {
          request_id: requestKey(message.id),
          backend_request_id: message.id,
          request_method: message.method,
          thread_id: threadId,
          turn_id: turnId,
          payload,
          at: nowIso(),
        });
        broadcastEvent = await this.store.appendEvent(threadId, event);
      }
      this.#broadcast(broadcastEvent);
      if (this.#shouldEmitSessionChanged(message.method, payload)) {
        this.#emitSessionChanged(threadId);
      }
      return;
    }

    if (message?.type === 'response') {
      return;
    }
  }

  #broadcast(message) {
    const data = JSON.stringify(message);
    for (const ws of this.connections) {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(data);
      }
    }
  }

  #emitSessionChanged(sessionId) {
    if (!sessionId) return;
    const session = this.store.getSession(sessionId);
    this.#broadcast(createEvent('session/changed', {
      session_id: sessionId,
      thread_id: session?.thread_id ?? sessionId,
      payload: {
        last_seq: this.store.getLastSeq(sessionId),
        updated_at: session?.updatedAt ?? nowIso(),
      },
    }));
  }

  #shouldEmitSessionChanged(eventName, payload = {}) {
    if (eventName === 'item/completed') {
      const itemType = payload?.item?.type ?? '';
      return itemType === 'agentMessage' || itemType === 'fileChange';
    }
    return eventName === 'thread/started'
      || eventName === 'thread/goal/updated'
      || eventName === 'thread/goal/cleared'
      || eventName === 'turn/started'
      || eventName === 'turn/completed'
      || eventName === 'item/commandExecution/requestApproval'
      || eventName === 'item/fileChange/requestApproval'
      || eventName === 'item/permissions/requestApproval'
      || eventName === 'applyPatchApproval'
      || eventName === 'execCommandApproval';
  }

  #compileApprovalResult(payload) {
    if (payload.decision !== undefined || payload.permissions !== undefined) {
      const result = {};
      if (payload.decision !== undefined) result.decision = payload.decision;
      if (payload.permissions !== undefined) result.permissions = payload.permissions;
      if (payload.scope !== undefined) result.scope = payload.scope;
      if (payload.strictAutoReview !== undefined) result.strictAutoReview = payload.strictAutoReview;
      return result;
    }
    return payload.result ?? payload;
  }
}
