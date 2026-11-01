import http from 'node:http';
import { createReadStream } from 'node:fs';
import fs from 'node:fs/promises';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import { WebSocket, WebSocketServer } from 'ws';
import { createError, createResponse, createEvent, nowIso } from './protocol.mjs';
import {
  backendName,
  getTurnInputItemId,
  isLoopbackHost,
  normalizeTurnStatusValue,
  requestKey,
  safeJson,
  summarizeApprovalPayload,
  summarizeClientPayload,
} from './bridge/common.mjs';
import {
  MAX_IMAGE_BYTES,
  describeSessionFile,
  readSessionFile,
  readSessionImage,
  writeSessionImageUpload,
  sweepSessionUploads,
  clampMaxBytes,
} from './bridge/file-reader.mjs';
import { listDirectories } from './bridge/directory-reader.mjs';
import { SessionContentController } from './bridge/session-content-controller.mjs';
import { ExternalSessionMonitor } from './bridge/external-session-monitor.mjs';
import {
  defaultSandboxPolicy,
  isThreadNotFoundError,
  mergeSessionSummary,
  normalizePermissionSelection,
  normalizeSandboxPolicy,
  normalizeThreadSummary,
  toSessionListItem,
} from './bridge/session-normalization.mjs';
import {
  normalizeSeq,
  nowLocalIso,
  summarizeSyncEntries,
  syncItemTypeCounts,
} from './bridge/sync-log.mjs';

// The hello payload must not drift from the published package version.
const require = createRequire(import.meta.url);
const { version: PACKAGE_VERSION } = require('../package.json');

const HEARTBEAT_INTERVAL_MS = 30_000;
// A client that cannot keep up is closed instead of letting its socket buffer
// grow until the bridge runs out of memory.
const MAX_BUFFERED_BYTES = 4 * 1024 * 1024;
// ws.close() waits closeTimeout (30 s by default) for the peer's close frame.
// A socket dropped for backpressure must not be kept alive that long.
const DROP_CLOSE_TIMEOUT_MS = 2_000;
const UPLOADS_SWEEP_INTERVAL_MS = 6 * 60 * 60 * 1000;

// Never persist the bridge token in sync logs.
function redactTokenFromUrl(rawUrl) {
  return String(rawUrl ?? '').replace(/([?&]token=)[^&]*/gi, '$1***');
}

async function readRequestBody(req, maxBytes) {
  const declaredBytes = Number(req.headers['content-length']);
  if (Number.isFinite(declaredBytes) && declaredBytes > maxBytes) {
    const error = new Error(`request exceeds ${maxBytes} bytes`);
    error.code = 'IMAGE_TOO_LARGE';
    throw error;
  }
  const chunks = [];
  let totalBytes = 0;
  for await (const chunk of req) {
    const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    totalBytes += bytes.byteLength;
    if (totalBytes > maxBytes) {
      const error = new Error(`request exceeds ${maxBytes} bytes`);
      error.code = 'IMAGE_TOO_LARGE';
      throw error;
    }
    chunks.push(bytes);
  }
  return Buffer.concat(chunks, totalBytes);
}

function normalizeHistoryMonths(value) {
  const months = Number(value);
  return Number.isFinite(months) && months > 0 ? Math.min(Math.floor(months), 120) : 0;
}

// Settings are read as camelCase by every handler, but the wire is easy to get
// wrong (see docs/protocol.md). Every setting that can arrive as snake_case is
// aliased to its camelCase spelling once, before dispatch. An explicit
// camelCase key always wins; identifiers and backend passthrough payloads are
// left untouched.
const SETTING_ALIASES = [
  ['approvalPolicy', 'approval_policy'],
  ['approvalsReviewer', 'approvals_reviewer'],
  ['sandboxPolicy', 'sandbox_policy'],
  ['modelProvider', 'model_provider'],
  ['serviceTier', 'service_tier'],
  ['serviceName', 'service_name'],
  ['persistExtendedHistory', 'persist_extended_history'],
  ['baseInstructions', 'base_instructions'],
  ['developerInstructions', 'developer_instructions'],
  ['threadSource', 'thread_source'],
  ['sessionStartSource', 'session_start_source'],
  ['responsesapiClientMetadata', 'responsesapi_client_metadata'],
  ['strictAutoReview', 'strict_auto_review'],
  ['historyMonths', 'history_months'],
  ['excludeTurns', 'exclude_turns'],
  ['dynamicTools', 'dynamic_tools'],
  ['experimentalRawEvents', 'experimental_raw_events'],
  ['mockExperimentalField', 'mock_experimental_field'],
  ['outputSchema', 'output_schema'],
  ['collaborationMode', 'collaboration_mode'],
];

function withSettingAliases(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return payload;
  let alias = null;
  for (const [camel, snake] of SETTING_ALIASES) {
    if (!Object.hasOwn(payload, snake) || Object.hasOwn(payload, camel)) continue;
    alias ??= { ...payload };
    alias[camel] = payload[snake];
  }
  return alias ?? payload;
}

function sessionTimestampMillis(session) {
  const raw = session?.updatedAt ?? session?.createdAt;
  const numeric = Number(raw);
  if (Number.isFinite(numeric) && numeric > 0) {
    return numeric < 100_000_000_000 ? numeric * 1000 : numeric;
  }
  const parsed = Date.parse(String(raw ?? ''));
  return Number.isFinite(parsed) ? parsed : 0;
}

function historyCutoffMillis(months) {
  if (months <= 0) return 0;
  const cutoff = new Date();
  const targetDay = cutoff.getUTCDate();
  cutoff.setUTCDate(1);
  cutoff.setUTCMonth(cutoff.getUTCMonth() - months);
  const daysInTargetMonth = new Date(Date.UTC(cutoff.getUTCFullYear(), cutoff.getUTCMonth() + 1, 0)).getUTCDate();
  cutoff.setUTCDate(Math.min(targetDay, daysInTargetMonth));
  return cutoff.getTime();
}

function isBackendConnectionError(error) {
  if (!error || typeof error !== 'object' || Array.isArray(error)) return false;
  const connectionErrorKinds = new Set([
    'httpConnectionFailed',
    'responseStreamConnectionFailed',
    'responseStreamDisconnected',
    'responseTooManyFailedAttempts',
  ]);
  const errorInfo = error.codexErrorInfo;
  if (errorInfo && typeof errorInfo === 'object' && !Array.isArray(errorInfo)) {
    if (Object.keys(errorInfo).some((key) => connectionErrorKinds.has(key))) {
      return true;
    }
  }
  const detail = [error.code, error.message, error.additionalDetails]
    .filter((value) => typeof value === 'string')
    .join(' ')
    .toLowerCase();
  return detail.includes('request timed out')
    || detail.includes('connection timed out')
    || detail.includes('response stream disconnected')
    || detail.includes('reconnecting...');
}

function buildBackendConnectionFailure(error) {
  return {
    ...error,
    bridgeErrorCode: 'backend_connection_failed',
    userMessage: '电脑端 Codex 服务连接超时，请检查 VPN、代理或网络连接。',
  };
}

export class BridgeServer extends EventEmitter {
  constructor({
    backend,
    store,
    host = '127.0.0.1',
    port = 8787,
    token = null,
    bridgeId = null,
    name = 'codex-remote-control',
    version = PACKAGE_VERSION,
    syncLogPath = null,
    allowUnauthenticated = false,
    heartbeatIntervalMs = HEARTBEAT_INTERVAL_MS,
    dropCloseTimeoutMs = DROP_CLOSE_TIMEOUT_MS,
    externalSessionPollIntervalMs = 5000,
    externalSessionPollLimit = 50,
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
    this.allowUnauthenticated = allowUnauthenticated;
    this.httpServer = null;
    this.wsServer = null;
    this.connections = new Set();
    this.heartbeatIntervalMs = Math.max(1000, Number(heartbeatIntervalMs) || HEARTBEAT_INTERVAL_MS);
    this.dropCloseTimeoutMs = Math.max(0, Number(dropCloseTimeoutMs) || DROP_CLOSE_TIMEOUT_MS);
    this.heartbeatTimer = null;
    this.uploadsSweepTimer = null;
    this.backendListener = (message) => {
      this.#handleBackendMessage(message).catch((error) => {
        this.#logSync('backend.message.error', {
          message: error?.message ?? String(error),
        });
      });
    };
    this.sessionContent = new SessionContentController({
      backend: this.backend,
      store: this.store,
      logSync: (event, details) => this.#logSync(event, details),
    });
    this.externalSessionMonitor = new ExternalSessionMonitor({
      backend: this.backend,
      store: this.store,
      sessionContent: this.sessionContent,
      intervalMs: externalSessionPollIntervalMs,
      limit: externalSessionPollLimit,
      onEvents: (sessionId, events) => {
        for (const event of events) {
          this.#broadcast(event);
        }
        this.#emitSessionChanged(sessionId);
      },
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
    // An empty token accepts every client, so only allow it on loopback unless
    // the operator opts in explicitly.
    if (!this.token && !this.allowUnauthenticated && !isLoopbackHost(this.host)) {
      const error = new Error(
        `refusing to listen on ${this.host} without a token; set a token or pass --insecure`,
      );
      error.code = 'TOKEN_REQUIRED';
      this.#logSync('start.refused', { host: this.host, reason: 'missing_token' });
      throw error;
    }
    if (!this.token) {
      this.#logSync('start.unauthenticated', { host: this.host });
    }
    await this.store.load();
    await this.backend.start();
    this.backend.on('message', this.backendListener);

    this.httpServer = http.createServer((req, res) => this.#handleHttp(req, res));
    this.httpServer.on('error', (error) => {
      this.#logSync('http.server.error', { message: error?.message ?? String(error) });
    });
    // Cap frame size: ws defaults to 100 MiB, which lets one client stall the
    // event loop with a single synchronous JSON.parse.
    this.wsServer = new WebSocketServer({ server: this.httpServer, maxPayload: 8 * 1024 * 1024 });
    this.wsServer.on('connection', (ws, req) => this.#handleConnection(ws, req));
    this.wsServer.on('error', (error) => {
      this.#logSync('ws.server.error', { message: error?.message ?? String(error) });
    });

    await new Promise((resolve) => {
      this.httpServer.listen(this.port, this.host, resolve);
    });

    // Half-open sockets from a phone that slept or lost its network otherwise
    // stay in this.connections forever, keeping the external monitor running.
    this.heartbeatTimer = setInterval(() => this.#heartbeat(), this.heartbeatIntervalMs);
    this.heartbeatTimer.unref?.();

    this.#sweepUploads();
    this.uploadsSweepTimer = setInterval(() => this.#sweepUploads(), UPLOADS_SWEEP_INTERVAL_MS);
    this.uploadsSweepTimer.unref?.();

    return this;
  }

  #heartbeat() {
    for (const ws of this.connections) {
      if (ws.isAlive === false) {
        this.#logSync('ws.heartbeat.timeout', {
          remote: ws.remoteAddress ?? '',
          connections: this.connections.size,
        });
        this.#dropConnection(ws, 1001, 'heartbeat timeout');
        continue;
      }
      ws.isAlive = false;
      try {
        ws.ping();
      } catch {}
    }
  }

  #sweepUploads() {
    sweepSessionUploads(this.store)
      .then((result) => {
        if (result.removed > 0) {
          this.#logSync('uploads.swept', { removed: result.removed, kept: result.kept });
        }
      })
      .catch((error) => {
        this.#logSync('uploads.sweep.error', { message: error?.message ?? String(error) });
      });
  }

  async stop() {
    this.backend.off('message', this.backendListener);
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (this.uploadsSweepTimer) {
      clearInterval(this.uploadsSweepTimer);
      this.uploadsSweepTimer = null;
    }
    this.externalSessionMonitor.stop();
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

  async #handleHttp(req, res) {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    if (url.pathname === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true, name: this.name, version: this.version }));
      return;
    }
    if (url.pathname === '/v1/image') {
      await this.#handleImageRequest(req, res, url);
      return;
    }
    if (url.pathname === '/v1/file') {
      await this.#handleFileRequest(req, res, url);
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: false, error: 'not_found' }));
  }

  async #handleImageRequest(req, res, url) {
    if (req.method !== 'GET' && req.method !== 'POST') {
      res.writeHead(405, { 'content-type': 'application/json', allow: 'GET, POST' });
      res.end(JSON.stringify({ ok: false, error: 'method_not_allowed' }));
      return;
    }
    const providedToken = url.searchParams.get('token') ?? req.headers['x-bridge-token'];
    if (this.token && providedToken !== this.token) {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: 'unauthorized' }));
      return;
    }
    const sessionId = url.searchParams.get('session_id')?.trim() ?? '';
    if (!sessionId) {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: 'session_id_required' }));
      return;
    }

    try {
      if (req.method === 'POST') {
        const content = await readRequestBody(req, MAX_IMAGE_BYTES);
        const upload = await writeSessionImageUpload(
          this.store,
          sessionId,
          req.headers['content-type'],
          content,
        );
        res.writeHead(201, {
          'content-type': 'application/json',
          'cache-control': 'no-store',
        });
        res.end(JSON.stringify({
          ok: true,
          path: upload.resolvedPath,
          mimeType: upload.mimeType,
          bytes: upload.bytes,
        }));
        return;
      }
      const requestedPath = url.searchParams.get('path')?.trim() ?? '';
      if (!requestedPath) {
        res.writeHead(400, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ ok: false, error: 'path_required' }));
        return;
      }
      const image = await readSessionImage(this.store, sessionId, requestedPath);
      res.writeHead(200, {
        'content-type': image.mimeType,
        'content-length': image.bytes,
        'cache-control': 'private, max-age=60',
        'x-content-type-options': 'nosniff',
      });
      res.end(image.content);
    } catch (error) {
      const status = error?.code === 'PATH_OUTSIDE_SESSION'
        ? 403
        : error?.code === 'IMAGE_TOO_LARGE'
        ? 413
        : error?.code === 'UNSUPPORTED_IMAGE'
          ? 415
          : error?.code === 'EMPTY_IMAGE'
            ? 400
          : error?.code === 'SESSION_NOT_FOUND'
            ? 404
            : 404;
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: error?.code ?? 'image_not_found' }));
    }
  }

  async #handleFileRequest(req, res, url) {
    if (req.method !== 'GET') {
      res.writeHead(405, { 'content-type': 'application/json', allow: 'GET' });
      res.end(JSON.stringify({ ok: false, error: 'method_not_allowed' }));
      return;
    }
    const providedToken = url.searchParams.get('token') ?? req.headers['x-bridge-token'];
    if (this.token && providedToken !== this.token) {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: 'unauthorized' }));
      return;
    }
    const sessionId = url.searchParams.get('session_id')?.trim() ?? '';
    const requestedPath = url.searchParams.get('path')?.trim() ?? '';
    if (!sessionId) {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: 'session_id_required' }));
      return;
    }
    if (!requestedPath) {
      res.writeHead(400, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: 'path_required' }));
      return;
    }

    try {
      const file = await describeSessionFile(this.store, sessionId, requestedPath);
      const asciiName = file.name.replace(/[^\x20-\x7e]+/g, '_').replace(/["\\]/g, '_') || 'download';
      const encodedName = encodeURIComponent(file.name).replace(/['()*]/g, (value) => `%${value.charCodeAt(0).toString(16).toUpperCase()}`);
      res.writeHead(200, {
        'content-type': file.mimeType,
        'content-length': file.size,
        'content-disposition': `inline; filename="${asciiName}"; filename*=UTF-8''${encodedName}`,
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff',
      });
      await pipeline(createReadStream(file.resolvedPath), res);
    } catch (error) {
      if (res.headersSent) {
        res.destroy();
        return;
      }
      const status = error?.code === 'EACCES' || error?.code === 'PATH_OUTSIDE_SESSION'
        ? 403
        : error?.code === 'SESSION_NOT_FOUND' || error?.code === 'NOT_FILE' || error?.code === 'ENOENT'
          ? 404
          : 500;
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: error?.code ?? 'file_not_found' }));
    }
  }

  async #handleConnection(ws, req) {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
    // A missing error listener turns any malformed frame into an uncaught
    // exception that kills the whole bridge (pre-auth DoS).
    ws.on('error', (error) => {
      this.#logSync('ws.error', {
        remote: req.socket?.remoteAddress ?? '',
        message: error?.message ?? String(error),
      });
      this.#dropConnection(ws, 1001, 'socket error');
    });
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

    ws.isAlive = true;
    ws.on('pong', () => {
      ws.isAlive = true;
    });

    this.connections.add(ws);
    if (this.connections.size === 1) {
      this.externalSessionMonitor.start();
    }
    this.#logSync('ws.connected', {
      remote: req.socket?.remoteAddress ?? '',
      url: redactTokenFromUrl(req.url),
      connections: this.connections.size,
    });
    this.#send(ws, {
      type: 'hello',
      ok: true,
      payload: {
        name: this.name,
        version: this.version,
        bridge_id: this.bridgeId,
        server_time: nowIso(),
      },
    });

    ws.on('message', async (buffer) => {
      const raw = buffer.toString('utf8');
      const parsed = safeJson(raw);
      if (!parsed.ok) {
        this.#logSync('ws.message.bad_json', {
          remote: req.socket?.remoteAddress ?? '',
          bytes: Buffer.byteLength(raw),
        });
        this.#send(ws, createError(null, 'bad_json', 'invalid JSON'));
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
        this.#send(ws, createError(parsed.value?.id ?? null, 'internal_error', error?.message ?? 'internal error'));
      }
    });

    ws.on('close', () => {
      this.connections.delete(ws);
      if (this.connections.size === 0) {
        this.externalSessionMonitor.stop();
      }
      this.#logSync('ws.closed', {
        remote: req.socket?.remoteAddress ?? '',
        connections: this.connections.size,
      });
    });
  }

  async #handleClientMessage(ws, message) {
    const type = message?.type;
    const id = message?.id ?? randomUUID();
    const payload = withSettingAliases(message?.payload ?? {});

    switch (type) {
      case 'ping':
        this.#send(ws, createResponse(id, { pong: true }, 'pong'));
        return;
      case 'session.list': {
        const historyMonths = normalizeHistoryMonths(payload.history_months ?? payload.historyMonths);
        const cutoffMillis = historyCutoffMillis(historyMonths);
        const backendThreads = await this.backend.listThreads(payload);
        const sessionsById = new Map();
        const backendSessions = backendThreads.map((thread) => {
          const summary = normalizeThreadSummary(thread, backendName(this.backend));
          const stored = summary?.session_id ? this.store.getSession(summary.session_id) : null;
          return mergeSessionSummary(summary, stored);
        }).filter((session) => session?.session_id);
        await this.store.upsertSessions(backendSessions);
        for (const session of backendSessions) {
          sessionsById.set(session.session_id, session);
        }
        for (const session of this.store.listSessions()) {
          if (!sessionsById.has(session.session_id)) {
            sessionsById.set(session.session_id, session);
          }
        }
        const sessions = [...sessionsById.values()]
          .map(toSessionListItem)
          .filter(Boolean)
          .filter((session) => cutoffMillis <= 0 || sessionTimestampMillis(session) >= cutoffMillis)
          .sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')));
        this.#send(ws, createResponse(id, { sessions }));
        return;
      }
      case 'model.list': {
        const listed = typeof this.backend.listModels === 'function'
          ? await this.backend.listModels(payload)
          : { data: [], nextCursor: null };
        const data = Array.isArray(listed) ? listed : listed?.data ?? [];
        const nextCursor = Array.isArray(listed) ? null : listed?.nextCursor ?? null;
        this.#send(ws, createResponse(id, { data, nextCursor }));
        return;
      }
      case 'directory.list': {
        const result = await listDirectories(payload.path);
        this.#send(ws, createResponse(id, {
          path: result.resolvedPath,
          parent_path: result.parentPath,
          directories: result.directories,
          truncated: result.truncated,
        }));
        return;
      }
      case 'collaboration_mode.list': {
        const listed = typeof this.backend.listCollaborationModes === 'function'
          ? await this.backend.listCollaborationModes(payload)
          : { data: [] };
        const data = Array.isArray(listed) ? listed : listed?.data ?? [];
        this.#send(ws, createResponse(id, { data }));
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
        this.#send(ws, createResponse(id, { session }));
        return;
      }
      case 'session.resume': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const stored = this.store.getSession(sessionId);
        const resumed = await this.backend.resumeThread(sessionId, {
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
        const session = mergeSessionSummary(
          normalizeThreadSummary(resumed, backendName(this.backend)),
          stored,
        );
        await this.store.upsertSession(session);
        this.#send(ws, createResponse(id, { session }));
        return;
      }
      case 'session.update': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const current = this.store.getSession(sessionId);
        if (!current) {
          this.#send(ws, createError(id, 'not_found', `unknown session: ${sessionId}`));
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
            this.#send(ws, createError(id, 'invalid_request', 'invalid sandbox'));
            return;
          }
          patch.sandbox = normalizedSandbox;
          patch.sandboxOverride = true;
        }
        const session = await this.store.upsertSession(patch, { touch: true });
        this.#emitSessionChanged(sessionId);
        this.#send(ws, createResponse(id, { session }));
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
        const startedTurn = turn?.turn ?? turn;
        if (turnParams.model || turnParams.effort) {
          const sessionSettings = {
            session_id: sessionId,
          };
          if (turnParams.model) {
            sessionSettings.model = turnParams.model;
            sessionSettings.modelOverride = true;
          }
          if (turnParams.effort) {
            sessionSettings.reasoningEffort = turnParams.effort;
            sessionSettings.reasoningEffortOverride = true;
          }
          session = await this.store.upsertSession(sessionSettings);
        }
        if (startedTurn?.id) {
          const attachedInputEvent = await this.store.attachTurnIdToRequestEvent(sessionId, id, startedTurn.id);
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
        this.#send(ws, createResponse(id, { turn: startedTurn, session_id: sessionId }));
        return;
      }
      case 'turn.steer': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const turnId = payload.turn_id ?? payload.turnId ?? payload.expectedTurnId;
        if (!sessionId || !turnId) {
          this.#send(ws, createError(id, 'invalid_request', 'session_id and turn_id are required'));
          return;
        }

        const session = this.store.getSession(sessionId);
        if (!session) {
          this.#send(ws, createError(id, 'not_found', `unknown session: ${sessionId}`));
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
        this.#send(ws, createResponse(id, {
          turn_id: result?.turnId ?? result?.turn_id ?? turnId,
          session_id: sessionId,
        }));
        return;
      }
      case 'turn.interrupt': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        await this.backend.interruptTurn(
          payload.thread_id ?? session?.thread_id ?? sessionId,
          payload.turn_id ?? payload.turnId,
        );
        this.#send(ws, createResponse(id, { ok: true }));
        return;
      }
      case 'goal.get': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        const threadId = payload.thread_id ?? session?.thread_id ?? sessionId;
        if (!threadId) {
          this.#send(ws, createError(id, 'invalid_request', 'session_id or thread_id is required'));
          return;
        }
        const result = typeof this.backend.getThreadGoal === 'function'
          ? await this.backend.getThreadGoal(threadId)
          : { goal: null };
        this.#send(ws, createResponse(id, {
          session_id: sessionId,
          thread_id: threadId,
          goal: result?.goal ?? null,
        }));
        return;
      }
      case 'goal.set': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        const threadId = payload.thread_id ?? session?.thread_id ?? sessionId;
        const objective = typeof payload.objective === 'string' ? payload.objective.trim() : '';
        if (!threadId) {
          this.#send(ws, createError(id, 'invalid_request', 'session_id or thread_id is required'));
          return;
        }
        if (!objective) {
          this.#send(ws, createError(id, 'invalid_request', 'objective is required'));
          return;
        }
        if (typeof this.backend.setThreadGoal !== 'function') {
          this.#send(ws, createError(id, 'unsupported', 'thread goal is not supported by this backend'));
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
        this.#send(ws, createResponse(id, {
          session_id: sessionId,
          thread_id: threadId,
          goal: result?.goal ?? null,
        }));
        return;
      }
      case 'goal.clear': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const session = this.store.getSession(sessionId);
        const threadId = payload.thread_id ?? session?.thread_id ?? sessionId;
        if (!threadId) {
          this.#send(ws, createError(id, 'invalid_request', 'session_id or thread_id is required'));
          return;
        }
        const result = typeof this.backend.clearThreadGoal === 'function'
          ? await this.backend.clearThreadGoal(threadId)
          : { cleared: false };
        this.#send(ws, createResponse(id, {
          session_id: sessionId,
          thread_id: threadId,
          cleared: Boolean(result?.cleared),
        }));
        return;
      }
      case 'approval.response': {
        const clientRequestId = payload.request_id ?? payload.requestId;
        const sessionId = payload.session_id ?? payload.thread_id;
        const pendingApproval = this.store.getPendingApproval(sessionId, clientRequestId);
        if (!pendingApproval) {
          this.#send(ws, createError(id, 'unknown_approval', 'no pending approval for this request id'));
          return;
        }
        const allowedDecisions = Array.isArray(pendingApproval.available_decisions)
          ? pendingApproval.available_decisions.filter((value) => typeof value === 'string')
          : [];
        if (payload.decision != null && allowedDecisions.length > 0 && !allowedDecisions.includes(String(payload.decision))) {
          this.#send(ws, createError(id, 'invalid_decision', `decision not allowed: ${payload.decision}`));
          return;
        }
        const backendRequestId = pendingApproval.backend_request_id ?? clientRequestId;
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
        this.#send(ws, createResponse(id, { ok: true }));
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
        this.#send(ws, createResponse(id, sync));
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
        this.#send(ws, createResponse(id, content));
        return;
      }
      case 'file.read': {
        const sessionId = payload.session_id ?? payload.thread_id;
        const requestedPath = String(payload.path ?? '').trim();
        const maxBytes = clampMaxBytes(payload.max_bytes);
        if (!requestedPath) {
          this.#send(ws, createError(id, 'invalid_path', 'path is required'));
          return;
        }
        let result;
        try {
          result = await readSessionFile(this.store, sessionId, requestedPath, maxBytes);
        } catch (error) {
          this.#send(ws, createError(id, error?.code ?? 'file_error', error?.message ?? 'failed to read file'));
          return;
        }
        this.#send(ws, createResponse(id, {
          session_id: sessionId,
          path: requestedPath,
          resolved_path: result.resolvedPath,
          name: result.name,
          mime_type: result.mimeType,
          content_kind: result.contentKind,
          encoding: result.encoding,
          content: result.content,
          truncated: result.truncated,
          bytes: result.bytes,
          size: result.size,
          downloadable: result.downloadable,
        }));
        return;
      }
      default:
        this.#send(ws, createError(id, 'unknown_message', `unknown message type: ${type}`));
    }
  }

  async #handleBackendMessage(message) {
    if (message?.type === 'notification') {
      let payload = message.params ?? {};
      const threadId = payload.threadId ?? payload.thread_id ?? payload.thread?.id ?? payload.thread?.threadId ?? payload.thread?.sessionId ?? null;
      const turnId = payload.turnId ?? payload.turn_id ?? payload.turn?.id ?? null;
      const sessionId = threadId ?? payload.sessionId ?? payload.session_id ?? null;
      if (message.method === 'turn/completed') {
        const connectionError = this.#terminalTurnConnectionError(sessionId, turnId, payload);
        if (connectionError) {
          payload = {
            ...payload,
            error: connectionError,
            turn: {
              ...(payload.turn ?? {}),
              id: payload.turn?.id ?? turnId,
              status: payload.turn?.status ?? payload.status,
              error: connectionError,
            },
          };
        }
      }
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

  // Every write goes through here: the guard has to cover the large request
  // responses (session.content, session.sync), not only broadcasts.
  #send(ws, payload) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    const data = typeof payload === 'string' ? payload : JSON.stringify(payload);
    const bytes = Buffer.byteLength(data);
    // Count the frame that is about to be queued, so two large frames in a row
    // cannot both slip under the limit.
    if (ws.bufferedAmount + bytes > MAX_BUFFERED_BYTES) {
      this.#dropConnection(ws, 1009, 'client too slow', {
        buffered: ws.bufferedAmount,
        frame_bytes: bytes,
      });
      return false;
    }
    try {
      ws.send(data);
      return true;
    } catch (error) {
      this.#logSync('ws.send.error', { message: error?.message ?? String(error) });
      return false;
    }
  }

  // Single removal path so the external monitor cannot keep polling for a
  // socket that is already gone.
  #dropConnection(ws, code, reason, extra = {}) {
    if (!ws) return false;
    const removed = this.connections.delete(ws);
    if (removed) {
      this.#logSync('ws.dropped', {
        remote: ws.remoteAddress ?? '',
        code,
        reason,
        connections: this.connections.size,
        ...extra,
      });
    }
    try {
      if (code === 1009) {
        ws.close(code, reason);
        // A peer that never finishes the close handshake would otherwise keep
        // the socket (and, before this, the monitor) alive for closeTimeout.
        const timer = setTimeout(() => {
          try {
            ws.terminate();
          } catch {}
        }, this.dropCloseTimeoutMs);
        timer.unref?.();
      } else {
        ws.terminate();
      }
    } catch {}
    if (this.connections.size === 0) {
      this.externalSessionMonitor.stop();
    }
    return removed;
  }

  #broadcast(message) {
    const data = JSON.stringify(message);
    for (const ws of this.connections) {
      this.#send(ws, data);
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

  #terminalTurnConnectionError(sessionId, turnId, payload) {
    const status = normalizeTurnStatusValue(payload?.status ?? payload?.turn?.status);
    if (!sessionId || !turnId || !['failed', 'interrupted', 'cancelled', 'canceled'].includes(status)) {
      return null;
    }
    if (payload?.error || payload?.turn?.error) return null;

    const events = this.store.getSession(sessionId)?.events ?? [];
    for (let index = events.length - 1; index >= 0; index -= 1) {
      const event = events[index];
      if ((event?.turn_id ?? '') !== turnId || event?.event !== 'error') continue;
      const error = event?.payload?.error;
      if (isBackendConnectionError(error)) {
        return buildBackendConnectionFailure(error);
      }
    }
    return null;
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
