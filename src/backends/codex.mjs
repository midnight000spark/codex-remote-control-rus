import { EventEmitter } from 'node:events';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { nowIso } from '../protocol.mjs';

function parseJsonLine(line) {
  try {
    return JSON.parse(line);
  } catch {
    return null;
  }
}

export class CodexBackend extends EventEmitter {
  constructor({
    codexBin = 'codex',
    codexArgs = [],
    codexConfigs = [],
    experimental = true,
    requestTimeoutMs = 30000,
    clientName = 'codex-remote-control',
    clientVersion = '0.2.0',
    protocolLogPath = null,
  } = {}) {
    super();
    this.codexBin = codexBin;
    this.codexArgs = codexArgs;
    this.codexConfigs = codexConfigs;
    this.experimental = experimental;
    this.requestTimeoutMs = requestTimeoutMs;
    this.clientName = clientName;
    this.clientVersion = clientVersion;
    this.protocolLogPath = protocolLogPath;
    this.child = null;
    this.stdoutRl = null;
    this.stderrRl = null;
    this.pending = new Map();
    this.requestSeq = 0;
    this.started = false;
  }

  async start() {
    const args = ['app-server', '--listen', 'stdio://'];
    for (const config of this.codexConfigs) {
      args.push('-c', config);
    }
    args.push(...this.codexArgs);
    this.child = spawn(this.codexBin, args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
    });

    this.child.on('exit', (code, signal) => {
      for (const [id, pending] of this.pending) {
        clearTimeout(pending.timer);
        pending.reject?.(new Error(`codex app-server exited before replying to ${id}`));
        this.pending.delete(id);
      }
      this.emit('message', {
        type: 'notification',
        method: 'error',
        params: {
          code: 'codex_process_exit',
          message: `codex app-server exited (${code ?? 'null'}, ${signal ?? 'null'})`,
        },
      });
    });

    this.stdoutRl = readline.createInterface({ input: this.child.stdout });
    this.stderrRl = readline.createInterface({ input: this.child.stderr });
    this.stdoutRl.on('line', (line) => this.#handleLine(line));
    this.stderrRl.on('line', (line) => {
      if (line.trim()) {
        this.#protocolLog('codex-stderr', line);
        this.emit('message', {
          type: 'notification',
          method: 'warning',
          params: { message: line },
        });
      }
    });

    await this.#initialize();
    this.started = true;
  }

  async stop() {
    this.started = false;
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject?.(new Error('codex backend stopped'));
      this.pending.delete(id);
    }
    this.stdoutRl?.close();
    this.stderrRl?.close();
    if (this.child && !this.child.killed) {
      this.child.kill('SIGTERM');
    }
    this.child = null;
  }

  async listThreads(params = {}) {
    const result = await this.#request('thread/list', {
      cursor: params.cursor ?? null,
      limit: params.limit ?? null,
      sortKey: params.sortKey ?? null,
      sortDirection: params.sortDirection ?? null,
      modelProviders: params.modelProviders ?? null,
      sourceKinds: params.sourceKinds ?? null,
      archived: params.archived ?? null,
      cwd: params.cwd ?? null,
      useStateDbOnly: params.useStateDbOnly ?? false,
      searchTerm: params.searchTerm ?? null,
    });
    return result?.data ?? [];
  }

  async listModels(params = {}) {
    return this.#request('model/list', {
      cursor: params.cursor ?? null,
      limit: params.limit ?? null,
      includeHidden: params.includeHidden ?? false,
    });
  }

  async listCollaborationModes(params = {}) {
    return this.#request('collaborationMode/list', params);
  }

  async startThread(params = {}) {
    return this.#request('thread/start', {
      ...params,
      experimentalRawEvents: params.experimentalRawEvents ?? false,
      persistExtendedHistory: params.persistExtendedHistory ?? false,
    });
  }

  async resumeThread(threadId, params = {}) {
    return this.#request('thread/resume', {
      threadId,
      ...params,
      persistExtendedHistory: params.persistExtendedHistory ?? false,
    });
  }

  async readThread(threadId, params = {}) {
    return this.#request('thread/read', {
      threadId,
      includeTurns: params.includeTurns ?? true,
    });
  }

  async listTurnItems(threadId, turnId, params = {}) {
    return this.#request('thread/turns/items/list', {
      threadId,
      turnId,
      cursor: params.cursor ?? null,
      limit: params.limit ?? null,
      sortDirection: params.sortDirection ?? null,
    });
  }

  async startTurn(threadId, params = {}) {
    const inputText = params.text ?? '';
    return this.#request('turn/start', {
      threadId,
      input: this.#normalizeInput(params.input, inputText),
      responsesapiClientMetadata: params.responsesapiClientMetadata ?? null,
      environments: params.environments ?? null,
      cwd: params.cwd ?? null,
      approvalPolicy: params.approvalPolicy ?? null,
      approvalsReviewer: params.approvalsReviewer ?? null,
      sandboxPolicy: params.sandboxPolicy ?? null,
      permissions: params.permissions ?? null,
      model: params.model ?? null,
      serviceTier: params.serviceTier ?? null,
      effort: params.effort ?? null,
      summary: params.summary ?? null,
      personality: params.personality ?? null,
      outputSchema: params.outputSchema ?? null,
      collaborationMode: params.collaborationMode ?? null,
    });
  }

  async steerTurn(threadId, turnId, params = {}) {
    const inputText = params.text ?? '';
    return this.#request('turn/steer', {
      threadId,
      input: this.#normalizeInput(params.input, inputText),
      responsesapiClientMetadata: params.responsesapiClientMetadata ?? null,
      expectedTurnId: turnId,
    });
  }

  async interruptTurn(threadId, turnId) {
    return this.#request('turn/interrupt', { threadId, turnId });
  }

  async getThreadGoal(threadId) {
    return this.#request('thread/goal/get', { threadId });
  }

  async setThreadGoal(threadId, params = {}) {
    return this.#request('thread/goal/set', {
      threadId,
      objective: params.objective ?? null,
      status: params.status ?? null,
      tokenBudget: params.tokenBudget ?? null,
    });
  }

  async clearThreadGoal(threadId) {
    return this.#request('thread/goal/clear', { threadId });
  }

  async respondRequest(requestId, result, error = null) {
    if (error) {
      return this.#respond({ id: requestId, error });
    }
    return this.#respond({ id: requestId, result });
  }

  async #initialize() {
    await this.#request('initialize', {
      clientInfo: {
        name: this.clientName,
        title: 'Codex Remote Bridge',
        version: this.clientVersion,
      },
      capabilities: {
        experimentalApi: true,
        optOutNotificationMethods: null,
      },
    });
  }

  #handleLine(line) {
    this.#protocolLog('codex->bridge', line);
    const parsed = parseJsonLine(line);
    if (!parsed) return;

    if (parsed.method && parsed.id !== undefined && parsed.params !== undefined) {
      this.emit('message', {
        type: 'request',
        method: parsed.method,
        id: parsed.id,
        params: parsed.params,
      });
      return;
    }

    if (parsed.method && parsed.params !== undefined) {
      this.emit('message', {
        type: 'notification',
        method: parsed.method,
        params: parsed.params,
      });
      return;
    }

    if (parsed.id !== undefined && (Object.hasOwn(parsed, 'result') || Object.hasOwn(parsed, 'error'))) {
      const key = String(parsed.id);
      const pending = this.pending.get(key);
      if (pending) {
        this.pending.delete(key);
        clearTimeout(pending.timer);
        if (Object.hasOwn(parsed, 'error') && parsed.error) {
          pending.reject(new Error(typeof parsed.error === 'string' ? parsed.error : JSON.stringify(parsed.error)));
        } else {
          pending.resolve(parsed.result);
        }
      }
    }
  }

  async #request(method, params) {
    const id = String(++this.requestSeq);
    const request = { id, method, params };
    const line = JSON.stringify(request);
    this.#protocolLog('bridge->codex', line);
    const result = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`request timeout for ${method}`));
      }, this.requestTimeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(`${line}\n`);
    });
    return result;
  }

  async #respond(message) {
    const line = JSON.stringify(message);
    this.#protocolLog('bridge->codex', line);
    this.child.stdin.write(`${line}\n`);
  }

  #protocolLog(direction, line) {
    if (!this.protocolLogPath) return;
    try {
      fs.mkdirSync(path.dirname(this.protocolLogPath), { recursive: true });
      fs.appendFileSync(this.protocolLogPath, `[${nowIso()}] ${direction} ${line}\n`, 'utf8');
    } catch {}
  }

  #normalizeInput(input, fallbackText) {
    if (Array.isArray(input)) return input;
    const text = typeof input === 'string' ? input : fallbackText;
    return [{
      type: 'text',
      text: text ?? '',
      text_elements: [],
    }];
  }
}
