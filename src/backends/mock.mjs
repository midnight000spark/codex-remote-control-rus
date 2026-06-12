import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';
import { nowIso, nowMs } from '../protocol.mjs';

function makeThreadRecord(sessionId, cfg = {}) {
  const at = nowIso();
  return {
    id: sessionId,
    sessionId,
    forkedFromId: null,
    preview: cfg.preview ?? cfg.title ?? '',
    ephemeral: false,
    model: cfg.model ?? '',
    modelProvider: cfg.modelProvider ?? 'mock',
    createdAt: Math.floor(Date.now() / 1000),
    updatedAt: Math.floor(Date.now() / 1000),
    status: 'active',
    path: null,
    cwd: cfg.cwd ?? process.cwd(),
    cliVersion: 'mock',
    source: 'app-server',
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: null,
    name: cfg.title ?? 'Mock session',
    turns: [],
    _meta: { at },
  };
}

function makeTurnRecord(turnId) {
  return {
    id: turnId,
    items: [],
    itemsView: 'summary',
    status: 'running',
    error: null,
    startedAt: nowMs(),
    completedAt: null,
    durationMs: null,
  };
}

export class MockBackend extends EventEmitter {
  constructor() {
    super();
    this.threads = new Map();
    this.pendingApprovals = new Map();
    this.turnControllers = new Map();
    this.turnSteers = new Map();
    this.goals = new Map();
    this.requestSeq = 0;
    this.turnSeq = 0;
    this.threadSeq = 0;
    this.started = false;
  }

  async start() {
    this.started = true;
  }

  async stop() {
    this.started = false;
    for (const controller of this.turnControllers.values()) {
      controller.abort();
    }
    this.turnControllers.clear();
    this.turnSteers.clear();
    this.pendingApprovals.clear();
    this.goals.clear();
  }

  async listThreads() {
    return [...this.threads.values()].map((thread) => structuredClone(thread));
  }

  async listModels() {
    return {
      data: [
        {
          id: 'mock-gpt-5',
          model: 'mock-gpt-5',
          displayName: 'Mock GPT-5',
          description: '用于本地联调的默认 mock 模型。',
          hidden: false,
          isDefault: true,
        },
        {
          id: 'mock-gpt-5-mini',
          model: 'mock-gpt-5-mini',
          displayName: 'Mock GPT-5 Mini',
          description: '更轻量的 mock 机型，方便测试切换。',
          hidden: false,
          isDefault: false,
        },
      ],
      nextCursor: null,
    };
  }

  async listCollaborationModes() {
    return {
      data: [
        { name: '普通', mode: 'default', model: null, reasoning_effort: null },
        { name: 'Plan', mode: 'plan', model: null, reasoning_effort: null },
      ],
    };
  }

  async startThread(params = {}) {
    const id = params.threadId ?? `mock_thread_${String(++this.threadSeq).padStart(4, '0')}`;
    const thread = makeThreadRecord(id, params);
    this.threads.set(id, thread);
    this.emit('message', {
      type: 'notification',
      method: 'thread/started',
      params: { thread: structuredClone(thread) },
    });
    return structuredClone(thread);
  }

  async resumeThread(threadId, params = {}) {
    const thread = this.threads.get(threadId) ?? makeThreadRecord(threadId, params);
    thread.status = 'active';
    thread.updatedAt = Math.floor(Date.now() / 1000);
    if (params.title) thread.name = params.title;
    if (params.model) thread.model = params.model;
    this.threads.set(threadId, thread);
    this.emit('message', {
      type: 'notification',
      method: 'thread/started',
      params: { thread: structuredClone(thread) },
    });
    return structuredClone(thread);
  }

  async readThread(threadId) {
    const thread = this.threads.get(threadId);
    if (!thread) {
      throw new Error(`unknown thread: ${threadId}`);
    }
    return { thread: structuredClone(thread) };
  }

  async listTurnItems(threadId, turnId) {
    const thread = this.threads.get(threadId);
    if (!thread) {
      throw new Error(`unknown thread: ${threadId}`);
    }
    const turn = thread.turns.find((entry) => entry.id === turnId);
    if (!turn) {
      throw new Error(`unknown turn: ${turnId}`);
    }
    return {
      data: structuredClone(turn.items ?? []),
      nextCursor: null,
      backwardsCursor: null,
    };
  }

  async startTurn(threadId, params = {}) {
    const thread = this.threads.get(threadId);
    if (!thread) throw new Error(`unknown thread: ${threadId}`);
    if (params.model) {
      thread.model = params.model;
    }
    const turnId = params.turnId ?? `mock_turn_${String(++this.turnSeq).padStart(4, '0')}`;
    const turn = makeTurnRecord(turnId);
    thread.updatedAt = Math.floor(Date.now() / 1000);
    thread.turns.push(structuredClone(turn));
    this.emit('message', {
      type: 'notification',
      method: 'turn/started',
      params: { threadId, turn: structuredClone(turn) },
    });

    const controller = new AbortController();
    this.turnControllers.set(turnId, controller);
    this.turnSteers.set(turnId, []);
    queueMicrotask(() => this.#runTurn(threadId, turnId, params, controller.signal));
    return structuredClone(turn);
  }

  async steerTurn(threadId, turnId, params = {}) {
    if (!this.turnControllers.has(turnId)) {
      throw new Error(`active turn not found: ${turnId}`);
    }
    const inputText = this.#extractText(params.input) || params.text || '';
    const steerText = inputText || '空追加指令';
    const steers = this.turnSteers.get(turnId) ?? [];
    steers.push(steerText);
    this.turnSteers.set(turnId, steers);
    this.emit('message', {
      type: 'notification',
      method: 'item/agentMessage/delta',
      params: {
        threadId,
        turnId,
        itemId: `item_${turnId}`,
        delta: `追加指令: ${steerText}`,
      },
    });
    return { turnId };
  }

  async interruptTurn(threadId, turnId) {
    const controller = this.turnControllers.get(turnId);
    if (controller) {
      controller.abort();
      this.turnControllers.delete(turnId);
      this.turnSteers.delete(turnId);
    }
    this.emit('message', {
      type: 'notification',
      method: 'turn/completed',
      params: {
        threadId,
        turn: {
          id: turnId,
          items: [],
          itemsView: 'summary',
          status: 'interrupted',
          error: null,
          startedAt: null,
          completedAt: nowMs(),
          durationMs: null,
        },
      },
    });
  }

  async getThreadGoal(threadId) {
    return { goal: structuredClone(this.goals.get(threadId) ?? null) };
  }

  async setThreadGoal(threadId, params = {}) {
    const now = nowMs();
    const existing = this.goals.get(threadId);
    const goal = {
      threadId,
      objective: params.objective ?? existing?.objective ?? '',
      status: params.status ?? existing?.status ?? 'active',
      tokenBudget: params.tokenBudget ?? existing?.tokenBudget ?? null,
      tokensUsed: existing?.tokensUsed ?? 0,
      timeUsedSeconds: existing?.timeUsedSeconds ?? 0,
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
    };
    this.goals.set(threadId, goal);
    this.emit('message', {
      type: 'notification',
      method: 'thread/goal/updated',
      params: { threadId, turnId: null, goal: structuredClone(goal) },
    });
    return { goal: structuredClone(goal) };
  }

  async clearThreadGoal(threadId) {
    const cleared = this.goals.delete(threadId);
    this.emit('message', {
      type: 'notification',
      method: 'thread/goal/cleared',
      params: { threadId },
    });
    return { cleared };
  }

  async respondRequest(requestId, result, error = null) {
    const pending = this.pendingApprovals.get(requestId);
    if (!pending) return;
    this.pendingApprovals.delete(requestId);
    pending.resolve({ result, error });
  }

  async #runTurn(threadId, turnId, params, signal) {
    const inputText = this.#extractText(params.input) || params.text || '';
    let chunks = [
      `收到: ${inputText || '空输入'}`,
      '这是 mock backend 的流式输出。',
      '后续可以替换为真实 Codex app-server。'
    ];

    if (/approve|approval|审批/i.test(inputText)) {
      const requestId = `mock_req_${++this.requestSeq}`;
      const approvalPromise = new Promise((resolve) => {
        this.pendingApprovals.set(requestId, { resolve });
      });
      this.emit('message', {
        type: 'request',
        method: 'item/commandExecution/requestApproval',
        id: requestId,
        params: {
          threadId,
          turnId,
          itemId: `item_${requestId}`,
          startedAtMs: nowMs(),
          reason: 'mock backend requested approval',
          command: 'echo hello',
          cwd: process.cwd(),
          availableDecisions: ['accept', 'decline', 'cancel'],
        },
      });

      let timeoutId;
      const timeoutPromise = new Promise((resolve) => {
        timeoutId = setTimeout(() => resolve({ timeout: true }), 10000);
      });
      const race = await Promise.race([approvalPromise, timeoutPromise]);
      clearTimeout(timeoutId);

      this.pendingApprovals.delete(requestId);

      if (signal.aborted) return;
      if (!race?.timeout) {
        chunks = ['审批已收到，继续执行。', ...chunks];
      } else {
        chunks = ['审批超时，继续模拟。', ...chunks];
      }
    }

    for (const chunk of chunks) {
      if (signal.aborted) return;
      await sleep(280);
      this.emit('message', {
        type: 'notification',
        method: 'item/agentMessage/delta',
        params: {
          threadId,
          turnId,
          itemId: `item_${turnId}`,
          delta: chunk,
        },
      });
    }

    if (signal.aborted) return;
    this.emit('message', {
      type: 'notification',
      method: 'turn/completed',
      params: {
        threadId,
        turn: {
          id: turnId,
          items: [],
          itemsView: 'summary',
          status: 'completed',
          error: null,
          startedAt: nowMs(),
          completedAt: nowMs(),
          durationMs: 1000,
        },
      },
    });
    this.turnControllers.delete(turnId);
    this.turnSteers.delete(turnId);
  }

  #extractText(input) {
    if (typeof input === 'string') return input;
    if (!Array.isArray(input)) return '';
    return input
      .map((item) => item?.text ?? '')
      .filter(Boolean)
      .join('\n')
      .trim();
  }
}
