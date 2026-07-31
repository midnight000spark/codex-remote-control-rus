import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter, once } from 'node:events';
import WebSocket from 'ws';
import { StateStore } from '../src/store.mjs';
import { MockBackend } from '../src/backends/mock.mjs';
import { BridgeServer } from '../src/bridge-server.mjs';

test('bridge hello includes stable bridge id', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-id-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new MockBackend();
  const bridge = new BridgeServer({
    backend,
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
    bridgeId: 'bridge-test-id',
  });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  await waitFor(() => messages.find((m) => m.type === 'hello'));
  const hello = messages.find((m) => m.type === 'hello');
  assert.equal(hello.payload.bridge_id, 'bridge-test-id');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge rejects websocket clients with wrong token before hello', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-unauthorized-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new MockBackend();
  const bridge = new BridgeServer({
    backend,
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'expected-token',
    bridgeId: 'bridge-test-id',
  });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=wrong-token`);
  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  const [code, reasonBuffer] = await once(ws, 'close');
  const reason = Buffer.isBuffer(reasonBuffer) ? reasonBuffer.toString('utf8') : String(reasonBuffer);
  assert.equal(code, 1008);
  assert.equal(reason, 'unauthorized');
  assert.equal(messages.length, 0);

  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('state store clear removes stored bridge history', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-clear-state-test-'));
  const stateFile = path.join(dir, 'state.json');
  const store = new StateStore(stateFile);
  await store.load();
  await store.upsertSession({
    session_id: 'session_clear_1',
    thread_id: 'session_clear_1',
    title: 'Clear me',
    cwd: '/tmp/clear-me',
    model: 'gpt-5',
    backend: 'mock',
    preview: '',
    active: true,
  });
  await store.appendEvent('session_clear_1', {
    type: 'event',
    event: 'turn/input',
    session_id: 'session_clear_1',
    thread_id: 'session_clear_1',
    payload: { text: 'hello' },
  });

  assert.equal(store.listSessions().length, 1);
  await store.clear();
  assert.equal(store.listSessions().length, 0);

  const reloadedStore = new StateStore(stateFile);
  await reloadedStore.load();
  assert.equal(reloadedStore.listSessions().length, 0);
  await fs.rm(dir, { recursive: true, force: true });
});

test('mock backend produces events and approval flow', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '0', type: 'model.list', payload: { includeHidden: false } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '0'));
  const modelList = messages.find((m) => m.type === 'response' && m.id === '0');
  assert.equal(modelList.ok, true);
  assert.ok(modelList.payload.data.length >= 1);
  const chosenModel = modelList.payload.data[0].model;

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: 'Test session', model: chosenModel } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionStart = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(sessionStart.ok, true);
  assert.ok(sessionStart.payload.session.session_id);
  assert.equal(sessionStart.payload.session.model, chosenModel);

  const sessionId = sessionStart.payload.session.session_id;
  ws.send(JSON.stringify({ id: '1b', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1b'));
  const sessionList = messages.find((m) => m.type === 'response' && m.id === '1b');
  assert.equal(sessionList.ok, true);
  assert.equal(sessionList.payload.sessions[0].model, chosenModel);

  ws.send(JSON.stringify({ id: '2', type: 'turn.send', payload: { session_id: sessionId, text: 'please request approval' } }));
  await waitFor(() => messages.find((m) => m.type === 'event' && m.event === 'item/commandExecution/requestApproval'));

  const approvalEvent = messages.find((m) => m.type === 'event' && m.event === 'item/commandExecution/requestApproval');
  ws.send(JSON.stringify({
    id: '3',
    type: 'approval.response',
    payload: {
      session_id: sessionId,
      request_id: approvalEvent.request_id,
      decision: 'accept',
    },
  }));

  await waitFor(() => messages.find((m) => m.type === 'event' && m.event === 'turn/completed'));
  const turnCompleted = messages.find((m) => m.type === 'event' && m.event === 'turn/completed');
  assert.equal(turnCompleted.session_id, sessionId);
  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge exposes collaboration modes and thread goal lifecycle', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-goal-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: 'modes', type: 'collaboration_mode.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === 'modes'));
  const modes = messages.find((m) => m.type === 'response' && m.id === 'modes');
  assert.equal(modes.ok, true);
  assert.ok(modes.payload.data.some((mode) => mode.mode === 'plan'));

  ws.send(JSON.stringify({ id: 'start', type: 'session.start', payload: { title: 'Goal session', model: 'mock-gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === 'start'));
  const sessionId = messages.find((m) => m.type === 'response' && m.id === 'start').payload.session.session_id;

  ws.send(JSON.stringify({
    id: 'goal-set',
    type: 'goal.set',
    payload: { session_id: sessionId, objective: 'finish remote control goal support', tokenBudget: 1234 },
  }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === 'goal-set'));
  const goalSet = messages.find((m) => m.type === 'response' && m.id === 'goal-set');
  assert.equal(goalSet.ok, true);
  assert.equal(goalSet.payload.goal.objective, 'finish remote control goal support');
  assert.equal(goalSet.payload.goal.tokenBudget, 1234);
  assert.ok(messages.find((m) => m.type === 'event' && m.event === 'thread/goal/updated'));

  ws.send(JSON.stringify({ id: 'goal-get', type: 'goal.get', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === 'goal-get'));
  const goalGet = messages.find((m) => m.type === 'response' && m.id === 'goal-get');
  assert.equal(goalGet.payload.goal.objective, 'finish remote control goal support');

  ws.send(JSON.stringify({ id: 'goal-clear', type: 'goal.clear', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === 'goal-clear'));
  const goalClear = messages.find((m) => m.type === 'response' && m.id === 'goal-clear');
  assert.equal(goalClear.payload.cleared, true);
  assert.ok(messages.find((m) => m.type === 'event' && m.event === 'thread/goal/cleared'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge does not resume brand-new session before first turn', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-fresh-turn-test-'));

  class FreshSessionBackend extends EventEmitter {
    constructor() {
      super();
      this.resumeCalls = 0;
      this.startTurnCalls = 0;
    }

    async start() {}

    async stop() {}

    async startThread(params = {}) {
      return {
        thread: {
          id: 'fresh_thread_1',
          sessionId: 'fresh_thread_1',
          forkedFromId: null,
          preview: params.title ?? 'Fresh session',
          ephemeral: false,
          modelProvider: 'custom',
          createdAt: 1,
          updatedAt: 2,
          status: { type: 'idle' },
          path: null,
          cwd: params.cwd ?? '/tmp/fresh-workspace',
          cliVersion: 'mock',
          source: 'app-server',
          threadSource: null,
          agentNickname: null,
          agentRole: null,
          gitInfo: null,
          name: params.title ?? 'Fresh session',
          turns: [],
        },
        model: params.model ?? 'gpt-5',
        cwd: params.cwd ?? '/tmp/fresh-workspace',
      };
    }

    async resumeThread() {
      this.resumeCalls += 1;
      throw new Error('resumeThread should not run before first turn on a fresh session');
    }

    async startTurn(threadId, params = {}) {
      this.startTurnCalls += 1;
      assert.equal(threadId, 'fresh_thread_1');
      assert.equal(params.text, 'hello fresh');
      return {
        id: 'turn_fresh_1',
        items: [],
        itemsView: 'summary',
        status: 'inProgress',
        error: null,
        startedAt: 10,
        completedAt: null,
        durationMs: null,
      };
    }

    async interruptTurn() {}

    async respondRequest() {}
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new FreshSessionBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: 'Fresh session', model: 'gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionId = messages.find((m) => m.type === 'response' && m.id === '1').payload.session.session_id;

  ws.send(JSON.stringify({ id: '2', type: 'turn.send', payload: { session_id: sessionId, text: 'hello fresh' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const sendResponse = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(sendResponse.ok, true);
  assert.equal(sendResponse.payload.turn.id, 'turn_fresh_1');
  assert.equal(backend.startTurnCalls, 1);
  assert.equal(backend.resumeCalls, 0);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge forwards active turn text as steer', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-steer-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'session_for_steer',
    thread_id: 'thread_for_steer',
    title: 'Steer session',
    cwd: '/tmp/steer-workspace',
    model: 'gpt-5',
    backend: 'custom',
    preview: '',
    active: true,
  });

  class SteerBackend extends EventEmitter {
    constructor() {
      super();
      this.calls = [];
    }

    async start() {}

    async stop() {}

    async steerTurn(threadId, turnId, params = {}) {
      this.calls.push({ threadId, turnId, params });
      return { turnId };
    }
  }

  const backend = new SteerBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({
    id: '1',
    type: 'turn.steer',
    payload: {
      session_id: 'session_for_steer',
      turn_id: 'turn_active_1',
      text: 'please adjust course',
    },
  }));

  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const steerResponse = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(steerResponse.ok, true);
  assert.equal(steerResponse.payload.turn_id, 'turn_active_1');
  assert.equal(backend.calls.length, 1);
  assert.equal(backend.calls[0].threadId, 'thread_for_steer');
  assert.equal(backend.calls[0].turnId, 'turn_active_1');
  assert.equal(backend.calls[0].params.text, 'please adjust course');

  const inputEvent = messages.find((m) => m.type === 'event' && m.event === 'turn/input');
  assert.equal(inputEvent.session_id, 'session_for_steer');
  assert.equal(inputEvent.thread_id, 'thread_for_steer');
  assert.equal(inputEvent.turn_id, 'turn_active_1');
  assert.equal(inputEvent.payload.text, 'please adjust course');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content keeps stored steer input alongside hydrated thread items', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-steer-content-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'session_steer_content',
    thread_id: 'thread_steer_content',
    title: 'Steer content session',
    cwd: '/tmp/steer-content-workspace',
    model: 'gpt-5',
    backend: 'custom',
    preview: '',
    active: true,
    thread: {
      id: 'thread_steer_content',
      sessionId: 'thread_steer_content',
      status: { type: 'active' },
      turns: [],
    },
  });
  await store.appendEvent('session_steer_content', {
    type: 'event',
    event: 'turn/started',
    session_id: 'session_steer_content',
    thread_id: 'thread_steer_content',
    turn_id: 'turn_steer_content',
    payload: {
      turn: { id: 'turn_steer_content' },
    },
  });
  await store.appendEvent('session_steer_content', {
    type: 'event',
    event: 'turn/input',
    session_id: 'session_steer_content',
    thread_id: 'thread_steer_content',
    turn_id: 'turn_steer_content',
    request_id: 'steer_req_1',
    payload: {
      itemId: 'input_steer_req_1',
      item_id: 'input_steer_req_1',
      text: '追加一个约束',
      input: null,
    },
  });

  class HydratedThreadBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async readThread() {
      return {
        thread: {
          id: 'thread_steer_content',
          sessionId: 'thread_steer_content',
          forkedFromId: null,
          preview: 'Steer content session',
          ephemeral: false,
          modelProvider: 'custom',
          createdAt: 1,
          updatedAt: 2,
          status: { type: 'active' },
          path: null,
          cwd: '/tmp/steer-content-workspace',
          cliVersion: 'mock',
          source: 'app-server',
          threadSource: null,
          agentNickname: null,
          agentRole: null,
          gitInfo: null,
          name: 'Steer content session',
          turns: [{
            id: 'turn_steer_content',
            status: 'inProgress',
            startedAt: 10,
            items: [{
              type: 'agentMessage',
              id: 'agent_existing',
              text: '已有输出',
            }],
          }],
        },
      };
    }
  }

  const backend = new HydratedThreadBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({
    id: '1',
    type: 'session.content',
    payload: { session_id: 'session_steer_content' },
  }));

  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(contentResponse.ok, true);
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.id === 'agent_existing'));
  const steerEntry = contentResponse.payload.entries.find((entry) => entry.item?.id === 'input_steer_req_1');
  assert.equal(steerEntry?.turn_id, 'turn_steer_content');
  assert.equal(steerEntry?.item?.content?.[0]?.text, '追加一个约束');
  assert.equal(contentResponse.payload.active_turns[0]?.turn_id, 'turn_steer_content');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge preserves top-level model metadata from session start responses', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-start-model-test-'));

  class StartEnvelopeBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async startThread(params = {}) {
      return {
        thread: {
          id: 'envelope_thread_1',
          sessionId: 'envelope_thread_1',
          forkedFromId: null,
          preview: 'Envelope session',
          ephemeral: false,
          modelProvider: 'custom',
          createdAt: 1,
          updatedAt: 2,
          status: { type: 'idle' },
          path: null,
          cwd: params.cwd ?? '/tmp/envelope-workspace',
          cliVersion: 'mock',
          source: 'app-server',
          threadSource: null,
          agentNickname: null,
          agentRole: null,
          gitInfo: null,
          name: params.title ?? 'Envelope session',
          turns: [],
        },
        model: params.model ?? 'gpt-5',
        cwd: params.cwd ?? '/tmp/envelope-workspace',
      };
    }

    async listThreads() {
      return [{
        id: 'envelope_thread_1',
        sessionId: 'envelope_thread_1',
        forkedFromId: null,
        preview: 'Envelope session',
        ephemeral: false,
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp/envelope-workspace',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: 'Envelope session',
        turns: [],
      }];
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new StartEnvelopeBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: 'Envelope session', model: 'gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionStart = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(sessionStart.ok, true);
  assert.equal(sessionStart.payload.session.model, 'gpt-5');
  assert.equal(sessionStart.payload.session.cwd, '/tmp/envelope-workspace');

  ws.send(JSON.stringify({ id: '2', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const sessionList = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(sessionList.ok, true);
  assert.equal(sessionList.payload.sessions[0].model, 'gpt-5');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content skips thread.read for brand-new sessions without rollout or turns', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-empty-session-test-'));

  class EmptySessionBackend extends EventEmitter {
    constructor() {
      super();
      this.readThreadCalls = 0;
    }

    async start() {}

    async stop() {}

    async startThread(params = {}) {
      return {
        thread: {
          id: 'empty_thread_1',
          sessionId: 'empty_thread_1',
          forkedFromId: null,
          preview: params.title ?? 'Empty session',
          ephemeral: false,
          modelProvider: 'custom',
          createdAt: 1,
          updatedAt: 2,
          status: { type: 'idle' },
          path: null,
          cwd: params.cwd ?? '/tmp/empty-workspace',
          cliVersion: 'mock',
          source: 'app-server',
          threadSource: null,
          agentNickname: null,
          agentRole: null,
          gitInfo: null,
          name: params.title ?? 'Empty session',
          turns: [],
        },
        model: params.model ?? 'gpt-5',
        cwd: params.cwd ?? '/tmp/empty-workspace',
      };
    }

    async readThread() {
      this.readThreadCalls += 1;
      throw new Error('no rollout found for thread');
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new EmptySessionBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: 'Empty session', model: 'gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionId = messages.find((m) => m.type === 'response' && m.id === '1').payload.session.session_id;

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const sessionContent = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(sessionContent.ok, true);
  assert.deepEqual(sessionContent.payload.entries, []);
  assert.equal(backend.readThreadCalls, 0);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content skips thread.read after thread started event with no meaningful events', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-started-empty-session-test-'));

  class StartedEmptySessionBackend extends EventEmitter {
    constructor() {
      super();
      this.readThreadCalls = 0;
    }

    async start() {}

    async stop() {}

    async startThread(params = {}) {
      const thread = {
        id: 'started_empty_thread_1',
        sessionId: 'started_empty_thread_1',
        forkedFromId: null,
        preview: params.title ?? 'Started empty session',
        ephemeral: false,
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: '/tmp/started-empty-rollout.jsonl',
        cwd: params.cwd ?? '/tmp/started-empty-workspace',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: params.title ?? 'Started empty session',
        turns: [],
      };
      queueMicrotask(() => {
        this.emit('message', {
          type: 'notification',
          method: 'thread/started',
          params: { thread },
        });
      });
      return {
        thread,
        model: params.model ?? 'gpt-5',
        cwd: params.cwd ?? '/tmp/started-empty-workspace',
      };
    }

    async readThread() {
      this.readThreadCalls += 1;
      throw new Error('thread is not materialized yet');
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new StartedEmptySessionBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: 'Started empty session', model: 'gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionId = messages.find((m) => m.type === 'response' && m.id === '1').payload.session.session_id;
  await waitFor(() => messages.find((m) => m.type === 'event' && m.event === 'thread/started' && m.session_id === sessionId));

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const sessionContent = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(sessionContent.ok, true);
  assert.equal(backend.readThreadCalls, 0);
  assert.deepEqual(sessionContent.payload.entries, []);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge broadcasts stored seq values and backfills turn ids for synced user input', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-seq-test-'));

  class SeqBackfillBackend extends EventEmitter {
    constructor() {
      super();
      this.thread = this.#makeThread();
    }

    async start() {}

    async stop() {}

    async startThread(params = {}) {
      this.thread = this.#makeThread({
        name: params.title ?? 'Live session',
        preview: params.title ?? 'Live session',
        model: params.model ?? 'gpt-5',
      });
      return structuredClone(this.thread);
    }

    async startTurn(threadId, params = {}) {
      this.thread = {
        ...this.thread,
        id: threadId,
        sessionId: threadId,
        turns: [{
          id: 'turn_live_1',
          items: [
            {
              type: 'userMessage',
              id: 'item_user_live_1',
              content: [{ type: 'text', text: params.text ?? '', text_elements: [] }],
            },
            {
              type: 'agentMessage',
              id: 'item_agent_live_1',
              text: '收到',
              phase: null,
              memoryCitation: null,
            },
          ],
          itemsView: 'full',
          status: 'completed',
          error: null,
          startedAt: 1,
          completedAt: 2,
          durationMs: 1,
        }],
      };
      return {
        id: 'turn_live_1',
        items: [],
        itemsView: 'summary',
        status: 'inProgress',
        error: null,
        startedAt: 10,
        completedAt: null,
        durationMs: null,
      };
    }

    async readThread() {
      return { thread: structuredClone(this.thread) };
    }

    async interruptTurn() {}

    async respondRequest() {}

    #makeThread(overrides = {}) {
      return {
        id: 'live_thread_1',
        sessionId: 'live_thread_1',
        forkedFromId: null,
        preview: 'Live session',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: 'Live session',
        turns: [],
        ...overrides,
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new SeqBackfillBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: 'Live session', model: 'gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionId = messages.find((m) => m.type === 'response' && m.id === '1').payload.session.session_id;

  ws.send(JSON.stringify({ id: '2', type: 'turn.send', payload: { session_id: sessionId, text: '继续' } }));
  await waitFor(() => messages.find((m) => m.type === 'event' && m.event === 'turn/input' && m.payload?.text === '继续'));
  const liveTurnInput = messages.find((m) => m.type === 'event' && m.event === 'turn/input' && m.payload?.text === '继续');
  assert.equal(typeof liveTurnInput.seq, 'number');
  assert.ok(liveTurnInput.seq > 0);
  assert.equal(liveTurnInput.payload.itemId, 'input_2');
  assert.equal(liveTurnInput.payload.item_id, 'input_2');

  await waitFor(() => {
    const session = store.getSession(sessionId);
    return (session?.events ?? []).filter((event) => event.event === 'turn/input' && event.payload?.itemId === 'input_2').length >= 2;
  });
  const boundTurnInput = messages.find((m) => (
    m.type === 'event'
    && m.event === 'turn/input'
    && m.turn_id === 'turn_live_1'
    && m.payload?.itemId === 'input_2'
  ));
  assert.ok(boundTurnInput);
  assert.equal(typeof boundTurnInput.seq, 'number');
  assert.ok(boundTurnInput.seq > liveTurnInput.seq);
  assert.equal(boundTurnInput.replaces_seq, liveTurnInput.seq);
  const storedInputEvents = store.getSession(sessionId).events.filter((event) => event.event === 'turn/input' && event.payload?.itemId === 'input_2');
  assert.equal(storedInputEvents.length, 2);
  assert.equal(storedInputEvents[0].turn_id, 'turn_live_1');
  assert.equal(storedInputEvents[1].turn_id, 'turn_live_1');
  assert.equal(storedInputEvents[1].replaces_seq, storedInputEvents[0].seq);

  ws.send(JSON.stringify({ id: '3', type: 'session.sync', payload: { session_id: sessionId, since_seq: 0 } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '3'));
  const syncResponse = messages.find((m) => m.type === 'response' && m.id === '3');
  const userInputs = syncResponse.payload.entries.filter((entry) => entry.item?.type === 'userMessage' && entry.item.content?.[0]?.text === '继续');
  assert.equal(userInputs.length, 1);
  assert.equal(userInputs[0].turn_id, 'turn_live_1');
  assert.equal(userInputs[0].item.id, 'input_2');
  assert.deepEqual(syncResponse.payload.changed_turn_ids, ['turn_live_1']);
  assert.equal(syncResponse.payload.needs_full_sync, false);
  assert.ok(syncResponse.payload.entries.some((entry) => entry.item?.type === 'agentMessage' && entry.item.id === 'item_agent_live_1'));

  ws.send(JSON.stringify({ id: '4', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '4'));
  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '4');
  const userEntry = contentResponse.payload.entries.find((entry) => entry.item?.type === 'userMessage' && entry.item.content?.[0]?.text === '继续');
  assert.equal(userEntry?.item?.id, 'input_2');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge resumes notLoaded threads before send and hydrates history', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-resume-test-'));
  const rolloutPath = path.join(dir, 'resume-thread.jsonl');
  await fs.writeFile(rolloutPath, [
    JSON.stringify({ timestamp: '2026-01-01T00:00:00.000Z', type: 'event_msg', payload: { type: 'task_started', turn_id: 'turn_old' } }),
    JSON.stringify({ timestamp: '2026-01-01T00:00:00.100Z', type: 'event_msg', payload: { type: 'user_message', message: '旧消息' } }),
    JSON.stringify({ timestamp: '2026-01-01T00:00:00.200Z', type: 'event_msg', payload: { type: 'agent_message', message: '旧回复' } }),
    JSON.stringify({ timestamp: '2026-01-01T00:00:00.300Z', type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn_old' } }),
    '',
  ].join('\n'), 'utf8');

  class ResumeBackend extends EventEmitter {
    constructor() {
      super();
      this.resumeCalls = 0;
    }

    async start() {}

    async stop() {}

    async listThreads() {
      return [this.#thread(false)];
    }

    async resumeThread(threadId) {
      this.resumeCalls += 1;
      return {
        thread: this.#thread(true, threadId),
      };
    }

    async startTurn(threadId) {
      if (this.resumeCalls === 0) {
        throw new Error(`thread not found: ${threadId}`);
      }
      return {
        id: 'turn_live',
        items: [],
        itemsView: 'summary',
        status: 'inProgress',
        error: null,
        startedAt: 10,
        completedAt: null,
        durationMs: null,
      };
    }

    async interruptTurn() {}

    async respondRequest() {}

    #thread(loaded, threadId = 'resume_thread_1') {
      return {
        id: threadId,
        sessionId: threadId,
        forkedFromId: null,
        preview: '历史会话',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: loaded ? { type: 'idle' } : { type: 'notLoaded' },
        path: rolloutPath,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '历史会话',
        turns: loaded
          ? [{
            id: 'turn_old',
            items: [
              {
                type: 'userMessage',
                id: 'item_user',
                content: [{ type: 'text', text: '旧消息', text_elements: [] }],
              },
              {
                type: 'agentMessage',
                id: 'item_agent',
                text: '旧回复',
                phase: null,
                memoryCitation: null,
              },
            ],
            itemsView: 'full',
            status: 'completed',
            error: null,
            startedAt: 1,
            completedAt: 2,
            durationMs: 1,
          }]
          : [],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new ResumeBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionList = messages.find((m) => m.type === 'response' && m.id === '1');
  const sessionId = sessionList.payload.sessions[0].session_id;

  ws.send(JSON.stringify({ id: '2', type: 'turn.send', payload: { session_id: sessionId, text: '继续' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const sendResponse = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(sendResponse.ok, true);
  assert.equal(backend.resumeCalls, 1);

  ws.send(JSON.stringify({ id: '3', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '3'));
  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '3');
  assert.equal(contentResponse.ok, true);
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'userMessage' && entry.item.content?.[0]?.text === '旧消息'));
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'agentMessage' && entry.item.id === 'item_agent' && entry.item.text === '旧回复'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge normalizes sandbox metadata for session content', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-session-sandbox-meta-test-'));
  const sessionId = 'session_sandbox_meta_thread_1';
  const thread = {
    id: sessionId,
    sessionId,
    forkedFromId: null,
    preview: 'Sandbox meta session',
    ephemeral: false,
    model: 'gpt-5',
    modelProvider: 'custom',
    createdAt: 1,
    updatedAt: 2,
    status: { type: 'idle' },
    path: null,
    cwd: '/tmp/meta-workspace',
    cliVersion: 'mock',
    source: 'app-server',
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: null,
    name: 'Sandbox meta session',
    turns: [],
  };

  class SessionSandboxMetadataBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [{
        thread: structuredClone(thread),
        sandbox: { type: 'dangerFullAccess' },
      }];
    }

    async readThread() {
      return {
        thread: structuredClone(thread),
        sandbox: { type: 'dangerFullAccess' },
      };
    }

    async interruptTurn() {}

    async respondRequest() {}
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();

  const backend = new SessionSandboxMetadataBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionList = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.deepEqual(sessionList.payload.sessions[0].sandbox, { type: 'dangerFullAccess' });

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const sessionContent = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.deepEqual(sessionContent.payload.session.sandbox, { type: 'dangerFullAccess' });

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content deduplicates stored user messages when both turn input and completed item exist', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-dedupe-user-message-test-'));
  const sessionId = 'dedupe_user_message_thread_1';

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: sessionId,
    thread_id: sessionId,
    title: 'Dedupe session',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'custom',
    preview: 'Dedupe session',
    active: true,
    thread: {
      id: sessionId,
      sessionId,
      cwd: '/tmp',
      status: { type: 'idle' },
      turns: [],
    },
  });

  await store.appendEvent(sessionId, {
    type: 'event',
    event: 'turn/input',
    turn_id: 'turn_dup_1',
    payload: {
      text: '重复用户消息',
      input: null,
    },
  });
  await store.appendEvent(sessionId, {
    type: 'event',
    event: 'item/completed',
    turn_id: 'turn_dup_1',
    payload: {
      item: {
        type: 'userMessage',
        id: 'item_user_dup_1',
        content: [{ type: 'text', text: '重复用户消息' }],
      },
    },
  });
  await store.appendEvent(sessionId, {
    type: 'event',
    event: 'item/completed',
    turn_id: 'turn_dup_1',
    payload: {
      item: {
        type: 'agentMessage',
        id: 'item_agent_dup_1',
        text: '收到',
        phase: null,
        memoryCitation: null,
      },
    },
  });

  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const response = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(response.ok, true);

  const userMessages = response.payload.entries.filter((entry) => entry.item?.type === 'userMessage');
  assert.equal(userMessages.length, 1);
  assert.equal(userMessages[0].item.id, 'item_user_dup_1');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session sync does not expose stored assistant deltas as final entries', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-stored-test-'));
  const rolloutPath = path.join(dir, 'stored-thread.jsonl');
  await fs.writeFile(rolloutPath, '', 'utf8');

  class StoredEventBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [this.#thread()];
    }

    async resumeThread() {
      throw new Error('resume should not be called when stored events exist');
    }

    #thread() {
      return {
        id: 'stored_thread_1',
        sessionId: 'stored_thread_1',
        forkedFromId: null,
        preview: '已存历史',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: rolloutPath,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '已存历史',
        turns: [{
          id: 'turn_live',
          items: [{
            type: 'agentMessage',
            id: 'item_summary',
            text: '折叠后的摘要',
            phase: null,
            memoryCitation: null,
          }],
          itemsView: 'summary',
          status: 'completed',
          error: null,
          startedAt: 1,
          completedAt: 2,
          durationMs: 1,
        }],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.upsertSession({
    session_id: 'stored_thread_1',
    thread_id: 'stored_thread_1',
    title: '已存历史',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: '已存历史',
    active: true,
    thread: {
      id: 'stored_thread_1',
      sessionId: 'stored_thread_1',
      status: { type: 'idle' },
      path: rolloutPath,
      turns: [],
    },
  });
  await store.appendEvent('stored_thread_1', {
    type: 'event',
    event: 'item/agentMessage/delta',
    session_id: 'stored_thread_1',
    thread_id: 'stored_thread_1',
    turn_id: 'turn_live',
    payload: {
      itemId: 'item_live',
      delta: '第一段',
      text: '第一段',
      message: '第一段',
    },
  });

  const backend = new StoredEventBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  ws.send(JSON.stringify({ id: '2', type: 'session.sync', payload: { session_id: 'stored_thread_1', since_seq: 0 } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));

  const syncResponse = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(syncResponse.ok, true);
  assert.equal(syncResponse.payload.needs_full_sync, true);
  assert.deepEqual(syncResponse.payload.entries, []);
  assert.match(syncResponse.payload.fallback_reason, /missing final agentMessage/);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge hydrates full turn items when turn summaries are incomplete', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-items-test-'));

  class TurnItemsBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [this.#thread()];
    }

    async listTurnItems(threadId, turnId) {
      assert.equal(threadId, 'items_thread_1');
      assert.equal(turnId, 'turn_full');
      return {
        data: [
          {
            type: 'userMessage',
            id: 'item_user',
            content: [{ type: 'text', text: '你好', text_elements: [] }],
          },
          {
            type: 'agentMessage',
            id: 'item_part_1',
            text: '第一段',
            phase: null,
            memoryCitation: null,
          },
          {
            type: 'agentMessage',
            id: 'item_part_2',
            text: '第二段',
            phase: null,
            memoryCitation: null,
          },
        ],
        nextCursor: null,
        backwardsCursor: null,
      };
    }

    #thread() {
      return {
        id: 'items_thread_1',
        sessionId: 'items_thread_1',
        forkedFromId: null,
        preview: '完整 items',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '完整 items',
        turns: [{
          id: 'turn_full',
          items: [{
            type: 'agentMessage',
            id: 'item_summary',
            text: '折叠摘要',
            phase: null,
            memoryCitation: null,
          }],
          itemsView: 'summary',
          status: 'completed',
          error: null,
          startedAt: 1,
          completedAt: 2,
          durationMs: 1,
        }],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new TurnItemsBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: 'items_thread_1' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));

  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(contentResponse.ok, true);
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'userMessage' && entry.item.content?.[0]?.text === '你好'));
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'agentMessage' && entry.item.id === 'item_part_1' && entry.item.text === '第一段'));
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'agentMessage' && entry.item.id === 'item_part_2' && entry.item.text === '第二段'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session sync returns incremental entries with last_seq after refresh', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-incremental-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: '增量会话', model: 'mock-gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionId = messages.find((m) => m.type === 'response' && m.id === '1').payload.session.session_id;

  ws.send(JSON.stringify({ id: '2', type: 'turn.send', payload: { session_id: sessionId, text: 'hello incremental' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  await waitFor(() => messages.find((m) => m.type === 'event' && m.event === 'turn/completed'));

  ws.send(JSON.stringify({ id: '3', type: 'session.sync', payload: { session_id: sessionId, since_seq: 0 } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '3'));
  const fullSync = messages.find((m) => m.type === 'response' && m.id === '3');
  assert.equal(fullSync.ok, true);
  assert.ok(fullSync.payload.last_seq > 0);
  assert.ok(Array.isArray(fullSync.payload.entries));
  assert.equal(fullSync.payload.needs_full_sync, false);
  assert.ok(fullSync.payload.changed_turn_ids.length > 0);

  ws.send(JSON.stringify({ id: '4', type: 'session.sync', payload: { session_id: sessionId, since_seq: fullSync.payload.last_seq } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '4'));
  const incrementalSync = messages.find((m) => m.type === 'response' && m.id === '4');
  assert.equal(incrementalSync.ok, true);
  assert.deepEqual(incrementalSync.payload.entries, []);
  assert.deepEqual(incrementalSync.payload.changed_turn_ids, []);
  assert.equal(incrementalSync.payload.needs_full_sync, false);
  assert.equal(incrementalSync.payload.last_seq, fullSync.payload.last_seq);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('incremental session sync does not surface hydrated events from older turns', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-incremental-history-test-'));

  class IncrementalHistoryBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async readThread() {
      return { thread: this.#thread() };
    }

    #thread() {
      return {
        id: 'history_thread_1',
        sessionId: 'history_thread_1',
        forkedFromId: null,
        preview: '历史补全',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '历史补全',
        turns: [{
          id: 'old_turn_1',
          items: [
            {
              type: 'userMessage',
              id: 'old_user_1',
              content: [{ type: 'text', text: '旧问题', text_elements: [] }],
            },
            {
              type: 'agentMessage',
              id: 'old_agent_1',
              text: '旧回答',
              phase: null,
              memoryCitation: null,
            },
          ],
          itemsView: 'full',
          status: 'completed',
          error: null,
          startedAt: 1,
          completedAt: 2,
          durationMs: 1,
        }],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'history_thread_1',
    thread_id: 'history_thread_1',
    title: '历史补全',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: '历史补全',
    active: true,
    thread: {
      id: 'history_thread_1',
      sessionId: 'history_thread_1',
      status: { type: 'idle' },
      turns: [],
    },
  });
  await store.appendEvent('history_thread_1', {
    type: 'event',
    event: 'turn/started',
    session_id: 'history_thread_1',
    thread_id: 'history_thread_1',
    turn_id: 'old_turn_1',
    payload: {
      turn: { id: 'old_turn_1' },
    },
  });
  await store.appendEvent('history_thread_1', {
    type: 'event',
    event: 'turn/completed',
    session_id: 'history_thread_1',
    thread_id: 'history_thread_1',
    turn_id: 'old_turn_1',
    payload: {
      status: 'completed',
      turn: { id: 'old_turn_1', status: 'completed' },
    },
  });
  const currentEvent = await store.appendEvent('history_thread_1', {
    type: 'event',
    event: 'turn/input',
    session_id: 'history_thread_1',
    thread_id: 'history_thread_1',
    turn_id: 'current_turn_1',
    payload: {
      text: '当前问题',
      input: null,
    },
  });
  await store.appendEvent('history_thread_1', {
    type: 'event',
    event: 'item/completed',
    session_id: 'history_thread_1',
    thread_id: 'history_thread_1',
    turn_id: 'old_turn_1',
    payload: {
      item: {
        type: 'agentMessage',
        id: 'old_agent_1',
        text: '旧回答',
        phase: 'final_answer',
        memoryCitation: null,
      },
    },
  });

  const backend = new IncrementalHistoryBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.sync', payload: { session_id: 'history_thread_1', since_seq: currentEvent.seq } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const incrementalSync = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(incrementalSync.ok, true);
  assert.deepEqual(incrementalSync.payload.entries, []);
  assert.deepEqual(incrementalSync.payload.changed_turn_ids, []);
  assert.equal(incrementalSync.payload.needs_full_sync, false);
  assert.equal(incrementalSync.payload.last_seq, store.getLastSeq('history_thread_1'));

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: 'history_thread_1' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const fullSync = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(fullSync.ok, true);
  assert.ok(fullSync.payload.entries.some((entry) => entry.item?.type === 'userMessage' && entry.item.content?.[0]?.text === '旧问题'));
  assert.equal(
    fullSync.payload.entries.filter((entry) => entry.item?.type === 'agentMessage' && entry.item.id === 'old_agent_1' && entry.item.text === '旧回答').length,
    1,
  );

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session sync hydrates tail turns that only exist in authoritative snapshot', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-snapshot-tail-test-'));

  class SnapshotTailBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async readThread() {
      return { thread: this.#thread() };
    }

    #thread() {
      return {
        id: 'tail_thread_1',
        sessionId: 'tail_thread_1',
        forkedFromId: null,
        preview: '尾部增量',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 4,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '尾部增量',
        turns: [
          {
            id: 'snapshot_only_old_turn',
            items: [
              {
                type: 'userMessage',
                id: 'snapshot_only_old_user',
                content: [{ type: 'text', text: '只在快照里的旧问题', text_elements: [] }],
              },
              {
                type: 'agentMessage',
                id: 'snapshot_only_old_agent',
                text: '只在快照里的旧回答',
                phase: null,
                memoryCitation: null,
              },
            ],
            itemsView: 'full',
            status: 'completed',
            error: null,
            startedAt: 0.1,
            completedAt: 0.2,
            durationMs: 1,
          },
          {
            id: 'known_turn_1',
            items: [
              {
                type: 'userMessage',
                id: 'known_user_1',
                content: [{ type: 'text', text: '已同步问题', text_elements: [] }],
              },
              {
                type: 'agentMessage',
                id: 'known_agent_1',
                text: '已同步回答',
                phase: null,
                memoryCitation: null,
              },
            ],
            itemsView: 'full',
            status: 'completed',
            error: null,
            startedAt: 1,
            completedAt: 2,
            durationMs: 1,
          },
          {
            id: 'tail_turn_1',
            items: [
              {
                type: 'userMessage',
                id: 'tail_user_1',
                content: [{ type: 'text', text: '桌面侧新问题', text_elements: [] }],
              },
              {
                type: 'agentMessage',
                id: 'tail_agent_1',
                text: '桌面侧新回答',
                phase: null,
                memoryCitation: null,
              },
            ],
            itemsView: 'full',
            status: 'completed',
            error: null,
            startedAt: 3,
            completedAt: 4,
            durationMs: 1,
          },
        ],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'tail_thread_1',
    thread_id: 'tail_thread_1',
    title: '尾部增量',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: '尾部增量',
    active: true,
    thread: {
      id: 'tail_thread_1',
      sessionId: 'tail_thread_1',
      status: { type: 'idle' },
      turns: [],
    },
  });
  await store.appendEvent('tail_thread_1', {
    type: 'event',
    event: 'turn/started',
    session_id: 'tail_thread_1',
    thread_id: 'tail_thread_1',
    turn_id: 'known_turn_1',
    payload: { turn: { id: 'known_turn_1' } },
  });
  await store.appendEvent('tail_thread_1', {
    type: 'event',
    event: 'turn/input',
    session_id: 'tail_thread_1',
    thread_id: 'tail_thread_1',
    turn_id: 'known_turn_1',
    payload: {
      itemId: 'known_user_1',
      item_id: 'known_user_1',
      text: '已同步问题',
      input: [{ type: 'text', text: '已同步问题', text_elements: [] }],
    },
  });
  await store.appendEvent('tail_thread_1', {
    type: 'event',
    event: 'item/completed',
    session_id: 'tail_thread_1',
    thread_id: 'tail_thread_1',
    turn_id: 'known_turn_1',
    payload: {
      item: {
        type: 'agentMessage',
        id: 'known_agent_1',
        text: '已同步回答',
        phase: null,
        memoryCitation: null,
      },
    },
  });
  const cursorEvent = await store.appendEvent('tail_thread_1', {
    type: 'event',
    event: 'turn/completed',
    session_id: 'tail_thread_1',
    thread_id: 'tail_thread_1',
    turn_id: 'known_turn_1',
    payload: {
      status: 'completed',
      turn: { id: 'known_turn_1', status: 'completed' },
    },
  });

  const bridge = new BridgeServer({ backend: new SnapshotTailBackend(), store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.sync', payload: { session_id: 'tail_thread_1', since_seq: cursorEvent.seq } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sync = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(sync.ok, true);
  assert.equal(sync.payload.needs_full_sync, false);
  assert.ok(sync.payload.last_seq > cursorEvent.seq);
  assert.deepEqual(sync.payload.changed_turn_ids, ['tail_turn_1']);
  assert.ok(!sync.payload.entries.some((entry) => entry.turn_id === 'snapshot_only_old_turn'));
  assert.ok(sync.payload.entries.some((entry) => entry.turn_id === 'tail_turn_1' && entry.item?.type === 'userMessage' && entry.item.content?.[0]?.text === '桌面侧新问题'));
  assert.ok(sync.payload.entries.some((entry) => entry.turn_id === 'tail_turn_1' && entry.item?.type === 'agentMessage' && entry.item.text === '桌面侧新回答'));
  assert.ok(sync.payload.entries.some((entry) => entry.turn_id === 'tail_turn_1' && entry.type === 'turn_status' && entry.status === 'completed'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session sync hydrates assistant items added later to the known tail turn', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-tail-followup-test-'));

  class TailFollowupBackend extends EventEmitter {
    includeAssistant = false;

    async start() {}

    async stop() {}

    async readThread() {
      return { thread: this.#thread() };
    }

    #thread() {
      const tailItems = [
        {
          type: 'userMessage',
          id: 'tail_user_1',
          content: [{ type: 'text', text: '先出现的问题', text_elements: [] }],
        },
      ];
      if (this.includeAssistant) {
        tailItems.push({
          type: 'agentMessage',
          id: 'tail_agent_1',
          text: '稍后出现的回答',
          phase: null,
          memoryCitation: null,
        });
      }
      return {
        id: 'tail_followup_thread',
        sessionId: 'tail_followup_thread',
        forkedFromId: null,
        preview: '尾部补齐',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 4,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '尾部补齐',
        turns: [
          {
            id: 'known_turn_1',
            items: [
              {
                type: 'userMessage',
                id: 'known_user_1',
                content: [{ type: 'text', text: '已同步问题', text_elements: [] }],
              },
              {
                type: 'agentMessage',
                id: 'known_agent_1',
                text: '已同步回答',
                phase: null,
                memoryCitation: null,
              },
            ],
            itemsView: 'full',
            status: 'completed',
            error: null,
            startedAt: 1,
            completedAt: 2,
            durationMs: 1,
          },
          {
            id: 'tail_turn_1',
            items: tailItems,
            itemsView: 'full',
            status: 'interrupted',
            error: null,
            startedAt: 3,
            completedAt: 4,
            durationMs: 1,
          },
        ],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'tail_followup_thread',
    thread_id: 'tail_followup_thread',
    title: '尾部补齐',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: '尾部补齐',
    active: true,
    thread: {
      id: 'tail_followup_thread',
      sessionId: 'tail_followup_thread',
      status: { type: 'idle' },
      turns: [],
    },
  });
  await store.appendEvent('tail_followup_thread', {
    type: 'event',
    event: 'turn/started',
    session_id: 'tail_followup_thread',
    thread_id: 'tail_followup_thread',
    turn_id: 'known_turn_1',
    payload: { turn: { id: 'known_turn_1' } },
  });
  await store.appendEvent('tail_followup_thread', {
    type: 'event',
    event: 'turn/input',
    session_id: 'tail_followup_thread',
    thread_id: 'tail_followup_thread',
    turn_id: 'known_turn_1',
    payload: {
      itemId: 'known_user_1',
      item_id: 'known_user_1',
      text: '已同步问题',
      input: [{ type: 'text', text: '已同步问题', text_elements: [] }],
    },
  });
  await store.appendEvent('tail_followup_thread', {
    type: 'event',
    event: 'item/completed',
    session_id: 'tail_followup_thread',
    thread_id: 'tail_followup_thread',
    turn_id: 'known_turn_1',
    payload: {
      item: {
        type: 'agentMessage',
        id: 'known_agent_1',
        text: '已同步回答',
        phase: null,
        memoryCitation: null,
      },
    },
  });
  const cursorEvent = await store.appendEvent('tail_followup_thread', {
    type: 'event',
    event: 'turn/completed',
    session_id: 'tail_followup_thread',
    thread_id: 'tail_followup_thread',
    turn_id: 'known_turn_1',
    payload: {
      status: 'completed',
      turn: { id: 'known_turn_1', status: 'completed' },
    },
  });

  const backend = new TailFollowupBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.sync', payload: { session_id: 'tail_followup_thread', since_seq: cursorEvent.seq } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const firstSync = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(firstSync.ok, true);
  assert.deepEqual(firstSync.payload.changed_turn_ids, ['tail_turn_1']);
  assert.ok(firstSync.payload.entries.some((entry) => entry.turn_id === 'tail_turn_1' && entry.item?.type === 'userMessage'));
  assert.ok(!firstSync.payload.entries.some((entry) => entry.turn_id === 'tail_turn_1' && entry.item?.type === 'agentMessage'));

  backend.includeAssistant = true;
  ws.send(JSON.stringify({ id: '2', type: 'session.sync', payload: { session_id: 'tail_followup_thread', since_seq: firstSync.payload.last_seq } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const secondSync = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(secondSync.ok, true);
  assert.equal(secondSync.payload.needs_full_sync, false);
  assert.ok(secondSync.payload.last_seq > firstSync.payload.last_seq);
  assert.deepEqual(secondSync.payload.changed_turn_ids, ['tail_turn_1']);
  assert.ok(secondSync.payload.entries.some((entry) => entry.turn_id === 'tail_turn_1' && entry.item?.type === 'agentMessage' && entry.item.text === '稍后出现的回答'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session sync falls back when final assistant item is not authoritative', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-sync-fallback-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'fallback_thread_1',
    thread_id: 'fallback_thread_1',
    title: '缺少最终回复',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: '缺少最终回复',
    active: true,
    thread: {
      id: 'fallback_thread_1',
      sessionId: 'fallback_thread_1',
      status: { type: 'idle' },
      turns: [],
    },
  });
  await store.appendEvent('fallback_thread_1', {
    type: 'event',
    event: 'turn/started',
    session_id: 'fallback_thread_1',
    thread_id: 'fallback_thread_1',
    turn_id: 'fallback_turn_1',
    payload: {
      turn: { id: 'fallback_turn_1' },
    },
  });
  await store.appendEvent('fallback_thread_1', {
    type: 'event',
    event: 'item/completed',
    session_id: 'fallback_thread_1',
    thread_id: 'fallback_thread_1',
    turn_id: 'fallback_turn_1',
    payload: {
      item: {
        type: 'agentMessage',
        id: 'fallback_agent_1',
        text: '不能从 stored event 合成最终回复',
      },
    },
  });

  class MissingFinalBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async readThread() {
      return {
        thread: {
          id: 'fallback_thread_1',
          sessionId: 'fallback_thread_1',
          status: { type: 'idle' },
          turns: [{
            id: 'fallback_turn_1',
            items: [{
              type: 'userMessage',
              id: 'fallback_user_1',
              content: [{ type: 'text', text: '问题', text_elements: [] }],
            }],
            itemsView: 'full',
            status: 'completed',
            error: null,
            startedAt: 1,
            completedAt: 2,
            durationMs: 1,
          }],
        },
      };
    }
  }

  const backend = new MissingFinalBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.sync', payload: { session_id: 'fallback_thread_1', since_seq: 0 } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const syncResponse = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(syncResponse.ok, true);
  assert.equal(syncResponse.payload.needs_full_sync, true);
  assert.match(syncResponse.payload.fallback_reason, /missing final agentMessage/);
  assert.ok(!syncResponse.payload.entries.some((entry) => entry.item?.type === 'agentMessage'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session sync merges hydrated items for a turn even when stored events already exist', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-merge-test-'));

  class MergeBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [this.#thread()];
    }

    async readThread() {
      return { thread: this.#thread() };
    }

    async listTurnItems() {
      return {
        data: [
          {
            type: 'userMessage',
            id: 'merge_user',
            content: [{ type: 'text', text: '历史问题', text_elements: [] }],
          },
          {
            type: 'agentMessage',
            id: 'merge_part_1',
            text: '第一段',
            phase: null,
            memoryCitation: null,
          },
          {
            type: 'agentMessage',
            id: 'merge_part_2',
            text: '第二段',
            phase: null,
            memoryCitation: null,
          },
        ],
        nextCursor: null,
        backwardsCursor: null,
      };
    }

    #thread() {
      return {
        id: 'merge_thread_1',
        sessionId: 'merge_thread_1',
        forkedFromId: null,
        preview: '合并历史',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '合并历史',
        turns: [{
          id: 'merge_turn_1',
          items: [],
          itemsView: 'summary',
          status: 'completed',
          error: null,
          startedAt: 1,
          completedAt: 2,
          durationMs: 1,
        }],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.upsertSession({
    session_id: 'merge_thread_1',
    thread_id: 'merge_thread_1',
    title: '合并历史',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: '合并历史',
    active: true,
    thread: {
      id: 'merge_thread_1',
      sessionId: 'merge_thread_1',
      status: { type: 'idle' },
      turns: [],
    },
  });
  await store.appendEvent('merge_thread_1', {
    type: 'event',
    event: 'item/agentMessage/delta',
    session_id: 'merge_thread_1',
    thread_id: 'merge_thread_1',
    turn_id: 'merge_turn_1',
    payload: {
      itemId: 'stream_live',
      delta: '第一段',
      text: '第一段',
      message: '第一段',
    },
  });

  const backend = new MergeBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: 'merge_thread_1' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));

  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(contentResponse.ok, true);
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'userMessage' && entry.item.content?.[0]?.text === '历史问题'));
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'agentMessage' && entry.item.id === 'merge_part_1' && entry.item.text === '第一段'));
  assert.ok(contentResponse.payload.entries.some((entry) => entry.item?.type === 'agentMessage' && entry.item.id === 'merge_part_2' && entry.item.text === '第二段'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content returns final thread items and pending approvals in stable order', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-content-test-'));

  class ContentBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [this.#thread()];
    }

    async readThread() {
      return { thread: this.#thread() };
    }

    #thread() {
      return {
        id: 'content_thread_1',
        sessionId: 'content_thread_1',
        forkedFromId: null,
        preview: '内容快照',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 99,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '内容快照',
        turns: [{
          id: 'content_turn_1',
          items: [
            {
              type: 'userMessage',
              id: 'content_user_1',
              content: [{ type: 'text', text: '你好', text_elements: [] }],
            },
            {
              type: 'agentMessage',
              id: 'content_agent_1',
              text: '已收到',
              phase: null,
              memoryCitation: null,
            },
            {
              type: 'fileChange',
              id: 'content_file_1',
              status: 'completed',
              changes: [
                {
                  type: 'update',
                  path: 'src/app.ts',
                  unified_diff: '@@ -1 +1 @@\n-old\n+new\n',
                },
              ],
            },
          ],
          itemsView: 'full',
          status: 'completed',
          error: null,
          startedAt: 10,
          completedAt: 11,
          durationMs: 1,
        }, {
          id: 'content_turn_0',
          items: [
            {
              type: 'userMessage',
              id: 'content_user_0',
              content: [{ type: 'text', text: '更早', text_elements: [] }],
            },
            {
              type: 'agentMessage',
              id: 'content_agent_0',
              text: '更早回复',
              phase: null,
              memoryCitation: null,
            },
          ],
          itemsView: 'full',
          status: 'completed',
          error: null,
          startedAt: 1,
          completedAt: 2,
          durationMs: 1,
        }],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new ContentBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  await store.recordPendingApproval('content_thread_1', {
    request_id: 'approval_1',
    request_method: 'item/commandExecution/requestApproval',
    thread_id: 'content_thread_1',
    turn_id: 'content_turn_1',
    payload: {
      command: 'echo hello',
      availableDecisions: ['accept', 'decline'],
    },
    at: '2026-01-01T00:00:00.000Z',
  });

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: 'content_thread_1' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));

  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(contentResponse.ok, true);
  assert.deepEqual(
    contentResponse.payload.entries.map((entry) => entry.item?.type ?? entry.type),
    ['userMessage', 'agentMessage', 'turn_status', 'userMessage', 'agentMessage', 'fileChange', 'turn_status'],
  );
  assert.equal(contentResponse.payload.entries[0].item.id, 'content_user_0');
  assert.equal(contentResponse.payload.entries[1].item.id, 'content_agent_0');
  assert.equal(contentResponse.payload.entries[2].status, 'completed');
  assert.equal(contentResponse.payload.entries[3].item.id, 'content_user_1');
  assert.equal(contentResponse.payload.entries[4].item.id, 'content_agent_1');
  assert.equal(contentResponse.payload.entries[5].item.id, 'content_file_1');
  assert.equal(contentResponse.payload.entries[6].status, 'completed');
  assert.equal(contentResponse.payload.pending_approvals.length, 1);
  assert.equal(contentResponse.payload.pending_approvals[0].request_id, 'approval_1');
  assert.equal(store.getSession('content_thread_1').updatedAt, '1970-01-01T00:00:11.000Z');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content reports active stored turns', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-active-turn-test-'));

  class EmptyBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [];
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'active_turn_thread',
    thread_id: 'active_turn_thread',
    title: 'Active turn',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: 'Active turn',
    active: true,
    thread: {
      id: 'active_turn_thread',
      sessionId: 'active_turn_thread',
      status: { type: 'idle' },
      turns: [],
    },
  });
  await store.appendEvent('active_turn_thread', {
    type: 'event',
    event: 'turn/started',
    session_id: 'active_turn_thread',
    thread_id: 'active_turn_thread',
    turn_id: 'active_turn_1',
    payload: { turn: { id: 'active_turn_1' } },
  });
  await store.appendEvent('active_turn_thread', {
    type: 'event',
    event: 'turn/input',
    session_id: 'active_turn_thread',
    thread_id: 'active_turn_thread',
    turn_id: 'active_turn_1',
    payload: {
      text: '远端问题',
      input: null,
    },
  });
  await store.appendEvent('active_turn_thread', {
    type: 'event',
    event: 'item/agentMessage/delta',
    session_id: 'active_turn_thread',
    thread_id: 'active_turn_thread',
    turn_id: 'active_turn_1',
    payload: {
      itemId: 'active_agent_stream',
      delta: '处理中',
    },
  });

  const bridge = new BridgeServer({ backend: new EmptyBackend(), store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.content', payload: { session_id: 'active_turn_thread' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(contentResponse.ok, true);
  assert.equal(contentResponse.payload.active_turns.length, 1);
  assert.equal(contentResponse.payload.active_turns[0].turn_id, 'active_turn_1');
  assert.equal(contentResponse.payload.active_turns[0].thread_id, 'active_turn_thread');
  assert.equal(contentResponse.payload.active_turns[0].last_event, 'item/agentMessage/delta');
  assert.equal(contentResponse.payload.active_turns[0].last_item_type, 'agentMessage');
  assert.equal(contentResponse.payload.active_turns[0].output_chars, 3);
  assert.equal(contentResponse.payload.active_turns[0].detail, null);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content clears active turns when authoritative snapshot has terminal status', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-terminal-active-turn-test-'));

  class CompletedTurnBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async readThread() {
      return {
        thread: {
          id: 'terminal_active_thread',
          sessionId: 'terminal_active_thread',
          status: { type: 'idle' },
          turns: [{
            id: 'active_turn_1',
            items: [
              {
                type: 'userMessage',
                id: 'active_user_1',
                content: [{ type: 'text', text: '远端问题', text_elements: [] }],
              },
              {
                type: 'agentMessage',
                id: 'active_agent_1',
                text: '已完成',
                phase: null,
                memoryCitation: null,
              },
            ],
            itemsView: 'full',
            status: 'completed',
            error: null,
            startedAt: 1,
            completedAt: 2,
            durationMs: 1,
          }],
        },
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'terminal_active_thread',
    thread_id: 'terminal_active_thread',
    title: 'Terminal active turn',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'mock',
    preview: 'Terminal active turn',
    active: true,
    thread: {
      id: 'terminal_active_thread',
      sessionId: 'terminal_active_thread',
      status: { type: 'idle' },
      turns: [],
    },
  });
  await store.appendEvent('terminal_active_thread', {
    type: 'event',
    event: 'turn/started',
    session_id: 'terminal_active_thread',
    thread_id: 'terminal_active_thread',
    turn_id: 'active_turn_1',
    payload: { turn: { id: 'active_turn_1' } },
  });
  await store.appendEvent('terminal_active_thread', {
    type: 'event',
    event: 'item/agentMessage/delta',
    session_id: 'terminal_active_thread',
    thread_id: 'terminal_active_thread',
    turn_id: 'active_turn_1',
    payload: {
      itemId: 'active_agent_1',
      delta: '已',
    },
  });

  const bridge = new BridgeServer({ backend: new CompletedTurnBackend(), store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.content', payload: { session_id: 'terminal_active_thread' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(contentResponse.ok, true);
  assert.equal(contentResponse.payload.active_turns.length, 0);
  assert.ok(contentResponse.payload.entries.some((entry) => entry.turn_id === 'active_turn_1' && entry.type === 'turn_status' && entry.status === 'completed'));

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge emits session changed notifications for turn sends', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-session-changed-test-'));

  class SessionChangedBackend extends EventEmitter {
    constructor() {
      super();
      this.thread = this.#thread();
    }

    async start() {}

    async stop() {}

    async startThread() {
      return structuredClone(this.thread);
    }

    async startTurn(threadId, params = {}) {
      this.thread = {
        ...this.thread,
        id: threadId,
        sessionId: threadId,
        turns: [{
          id: 'session_changed_turn_1',
          items: [{
            type: 'userMessage',
            id: 'session_changed_user_1',
            content: [{ type: 'text', text: params.text ?? '', text_elements: [] }],
          }],
          itemsView: 'full',
          status: 'inProgress',
          error: null,
          startedAt: 1,
          completedAt: null,
          durationMs: null,
        }],
      };
      return {
        id: 'session_changed_turn_1',
        items: [],
        itemsView: 'summary',
        status: 'inProgress',
        error: null,
        startedAt: 1,
        completedAt: null,
        durationMs: null,
      };
    }

    async readThread() {
      return { thread: structuredClone(this.thread) };
    }

    async interruptTurn() {}

    async respondRequest() {}

    #thread() {
      return {
        id: 'session_changed_thread_1',
        sessionId: 'session_changed_thread_1',
        forkedFromId: null,
        preview: '变化通知',
        ephemeral: false,
        model: 'gpt-5',
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: null,
        cwd: '/tmp',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: '变化通知',
        turns: [],
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new SessionChangedBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.start', payload: { title: '变化通知', model: 'gpt-5' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionId = messages.find((m) => m.type === 'response' && m.id === '1').payload.session.session_id;

  ws.send(JSON.stringify({ id: '2', type: 'turn.send', payload: { session_id: sessionId, text: 'hello' } }));
  await waitFor(() => messages.find((m) => m.type === 'event' && m.event === 'session/changed' && m.session_id === sessionId));

  const changedEvent = messages.find((m) => m.type === 'event' && m.event === 'session/changed' && m.session_id === sessionId);
  assert.equal(changedEvent.payload.last_seq > 0, true);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge inherits stored sandbox policy for turn sends and defaults to full access', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-turn-sandbox-test-'));

  class TurnSandboxBackend extends EventEmitter {
    constructor() {
      super();
      this.paramsByThreadId = new Map();
    }

    async start() {}

    async stop() {}

    async startTurn(threadId, params = {}) {
      this.paramsByThreadId.set(threadId, structuredClone(params));
      return {
        id: `turn_${threadId}`,
        items: [],
        itemsView: 'summary',
        status: 'inProgress',
        error: null,
        startedAt: 1,
        completedAt: null,
        durationMs: null,
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'stored_sandbox_thread',
    thread_id: 'stored_sandbox_thread',
    title: 'stored sandbox',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'custom',
    preview: '',
    active: true,
    sandbox: { type: 'dangerFullAccess' },
    thread: { id: 'stored_sandbox_thread', sessionId: 'stored_sandbox_thread', status: { type: 'idle' }, turns: [] },
  });
  await store.upsertSession({
    session_id: 'missing_sandbox_thread',
    thread_id: 'missing_sandbox_thread',
    title: 'missing sandbox',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'custom',
    preview: '',
    active: true,
    thread: { id: 'missing_sandbox_thread', sessionId: 'missing_sandbox_thread', status: { type: 'idle' }, turns: [] },
  });

  const backend = new TurnSandboxBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'turn.send', payload: { session_id: 'stored_sandbox_thread', text: '继承沙箱' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  assert.deepEqual(backend.paramsByThreadId.get('stored_sandbox_thread')?.sandboxPolicy, { type: 'dangerFullAccess' });

  ws.send(JSON.stringify({ id: '2', type: 'turn.send', payload: { session_id: 'missing_sandbox_thread', text: '默认沙箱' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  assert.deepEqual(backend.paramsByThreadId.get('missing_sandbox_thread')?.sandboxPolicy, { type: 'dangerFullAccess' });

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge preserves stored sandbox while resuming before turn sends', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-resume-sandbox-test-'));

  class ResumeSandboxBackend extends EventEmitter {
    constructor() {
      super();
      this.resumeParams = null;
      this.turnParams = null;
    }

    async start() {}

    async stop() {}

    async resumeThread(threadId, params = {}) {
      this.resumeParams = { threadId, params: structuredClone(params) };
      return {
        id: threadId,
        sessionId: threadId,
        name: 'resume sandbox',
        preview: '',
        cwd: '/tmp',
        model: 'gpt-5',
        status: { type: 'idle' },
        sandbox: { type: 'dangerFullAccess' },
        turns: [],
      };
    }

    async startTurn(threadId, params = {}) {
      this.turnParams = { threadId, params: structuredClone(params) };
      return {
        id: `turn_${threadId}`,
        items: [],
        itemsView: 'summary',
        status: 'inProgress',
        error: null,
        startedAt: 1,
        completedAt: null,
        durationMs: null,
      };
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'not_loaded_full_access_thread',
    thread_id: 'not_loaded_full_access_thread',
    title: 'not loaded full access',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'custom',
    preview: '',
    active: true,
    sandbox: { type: 'dangerFullAccess' },
    thread: {
      id: 'not_loaded_full_access_thread',
      sessionId: 'not_loaded_full_access_thread',
      status: { type: 'notLoaded' },
      turns: [],
    },
  });

  const backend = new ResumeSandboxBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'turn.send', payload: { session_id: 'not_loaded_full_access_thread', text: '继续' } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  assert.equal(backend.resumeParams?.params.sandbox, 'danger-full-access');
  assert.deepEqual(backend.turnParams?.params.sandboxPolicy, { type: 'dangerFullAccess' });

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge updates stored session sandbox metadata', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-session-update-test-'));
  const backendThread = {
    id: 'update_sandbox_thread',
    sessionId: 'update_sandbox_thread',
    name: 'update sandbox',
    preview: '',
    cwd: '/tmp',
    model: 'gpt-5',
    status: { type: 'idle' },
    sandbox: { type: 'workspaceWrite', writableRoots: ['/tmp'], networkAccess: false },
    turns: [],
  };

  class SessionUpdateBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [structuredClone(backendThread)];
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'update_sandbox_thread',
    thread_id: 'update_sandbox_thread',
    title: 'update sandbox',
    cwd: '/tmp',
    model: 'gpt-5',
    backend: 'custom',
    preview: '',
    active: true,
    approvalPolicy: 'never',
    sandbox: { type: 'workspaceWrite', writableRoots: ['/tmp'], networkAccess: false },
    thread: backendThread,
  });

  const backend = new SessionUpdateBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({
    id: '1',
    type: 'session.update',
    payload: {
      session_id: 'update_sandbox_thread',
      sandbox: 'danger-full-access',
    },
  }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));

  const response = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.deepEqual(response.payload.session.sandbox, { type: 'dangerFullAccess' });
  assert.deepEqual(store.getSession('update_sandbox_thread').sandbox, { type: 'dangerFullAccess' });
  assert.ok(messages.some((m) => m.type === 'event' && m.event === 'session/changed' && m.session_id === 'update_sandbox_thread'));

  ws.send(JSON.stringify({ id: '2', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const listResponse = messages.find((m) => m.type === 'response' && m.id === '2');
  const listed = listResponse.payload.sessions.find((session) => session.session_id === 'update_sandbox_thread');
  assert.deepEqual(listed.sandbox, { type: 'dangerFullAccess' });

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge file.read resolves relative paths from session cwd', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-file-read-test-'));
  const workspaceDir = path.join(dir, 'workspace');
  await fs.mkdir(workspaceDir, { recursive: true });
  await fs.writeFile(path.join(workspaceDir, 'src-example.ts'), 'export const answer = 42;\n', 'utf8');

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'file_read_session',
    thread_id: 'file_read_session',
    title: '文件读取',
    cwd: workspaceDir,
    model: 'mock-gpt-5',
    backend: 'mock',
    preview: '',
    active: true,
    thread: {
      id: 'file_read_session',
      sessionId: 'file_read_session',
      cwd: workspaceDir,
      status: { type: 'idle' },
      turns: [],
    },
  });

  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({
    id: '1',
    type: 'file.read',
    payload: {
      session_id: 'file_read_session',
      path: 'src-example.ts',
    },
  }));

  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const response = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(response.ok, true);
  assert.equal(response.payload.content, 'export const answer = 42;\n');
  assert.equal(response.payload.resolved_path, path.join(workspaceDir, 'src-example.ts'));
  assert.equal(response.payload.truncated, false);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge serves authenticated session images', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-image-read-test-'));
  const workspaceDir = path.join(dir, 'workspace');
  const imageBytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');
  await fs.mkdir(workspaceDir, { recursive: true });
  await fs.writeFile(path.join(workspaceDir, 'pixel.png'), imageBytes);

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'image_read_session',
    thread_id: 'image_read_session',
    title: '图片读取',
    cwd: workspaceDir,
    model: 'mock-gpt-5',
    backend: 'mock',
    preview: '',
    active: true,
  });

  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const unauthorized = await fetch(
    `http://127.0.0.1:${port}/v1/image?session_id=image_read_session&path=pixel.png&token=wrong-token`,
  );
  assert.equal(unauthorized.status, 401);

  const response = await fetch(
    `http://127.0.0.1:${port}/v1/image?session_id=image_read_session&path=pixel.png&token=test-token`,
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-type'), 'image/png');
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), imageBytes);

  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('session content preserves user images and image thread items', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-image-content-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'image_content_session',
    thread_id: 'image_content_session',
    title: '图片会话',
    cwd: dir,
    model: 'mock-gpt-5',
    backend: 'mock',
    preview: '',
    active: true,
  });
  const userContent = [
    { type: 'text', text: '查看图片' },
    { type: 'image', url: 'https://example.com/remote.png' },
    { type: 'localImage', path: 'local.png' },
  ];
  await store.appendEvent('image_content_session', {
    type: 'event',
    event: 'turn/input',
    session_id: 'image_content_session',
    thread_id: 'image_content_session',
    turn_id: 'image_turn_1',
    payload: {
      itemId: 'image_user_1',
      item_id: 'image_user_1',
      text: '查看图片',
      input: userContent,
    },
  });
  await store.appendEvent('image_content_session', {
    type: 'event',
    event: 'item/completed',
    session_id: 'image_content_session',
    thread_id: 'image_content_session',
    turn_id: 'image_turn_1',
    payload: {
      item: { type: 'imageView', id: 'image_view_1', path: 'local.png' },
    },
  });
  await store.appendEvent('image_content_session', {
    type: 'event',
    event: 'item/completed',
    session_id: 'image_content_session',
    thread_id: 'image_content_session',
    turn_id: 'image_turn_1',
    payload: {
      item: {
        type: 'imageGeneration',
        id: 'image_generation_1',
        status: 'completed',
        revisedPrompt: 'green pixel',
        result: 'aGVsbG8=',
        savedPath: 'generated.png',
      },
    },
  });

  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');
  const messages = [];
  ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));

  ws.send(JSON.stringify({ id: '1', type: 'session.content', payload: { session_id: 'image_content_session' } }));
  await waitFor(() => messages.find((message) => message.type === 'response' && message.id === '1'));
  const response = messages.find((message) => message.type === 'response' && message.id === '1');
  assert.equal(response.ok, true);
  const items = response.payload.entries.filter((entry) => entry.type === 'item').map((entry) => entry.item);
  assert.deepEqual(items.find((item) => item.type === 'userMessage')?.content, userContent);
  assert.equal(items.find((item) => item.type === 'imageView')?.path, 'local.png');
  assert.equal(items.find((item) => item.type === 'imageGeneration')?.savedPath, 'generated.png');

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge preserves stored session metadata in session.list and session.content', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-session-meta-test-'));
  const sessionId = 'stored_meta_thread_1';
  const thread = {
    id: sessionId,
    sessionId,
    forkedFromId: null,
    preview: '已有会话',
    ephemeral: false,
    model: 'gpt-5',
    modelProvider: 'custom',
    createdAt: 1,
    updatedAt: 2,
    status: { type: 'idle' },
    path: null,
    cwd: '/tmp/meta-workspace',
    cliVersion: 'mock',
    source: 'app-server',
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: null,
    name: '已有会话',
    turns: [],
  };

  class SessionMetadataBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [structuredClone(thread)];
    }

    async readThread() {
      return { thread: structuredClone(thread) };
    }

    async interruptTurn() {}

    async respondRequest() {}
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: sessionId,
    thread_id: sessionId,
    title: '已有会话',
    cwd: '/tmp/meta-workspace',
    model: 'gpt-5',
    backend: 'custom',
    preview: '已有会话',
    active: true,
    approvalPolicy: 'on-request',
    permissions: { type: 'profile', id: ':workspace' },
    sandbox: { type: 'workspaceWrite', writableRoots: ['/tmp/meta-workspace'], networkAccess: false },
    contextWindow: 123456,
    lastTokenUsage: {
      totalTokens: 120,
      inputTokens: 70,
      cachedInputTokens: 10,
      outputTokens: 50,
      reasoningOutputTokens: 5,
    },
    totalTokenUsage: {
      totalTokens: 900,
      inputTokens: 450,
      cachedInputTokens: 100,
      outputTokens: 450,
      reasoningOutputTokens: 30,
    },
    thread,
  });

  const backend = new SessionMetadataBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionList = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(sessionList.ok, true);
  assert.equal(sessionList.payload.sessions.length, 1);
  assert.equal(sessionList.payload.sessions[0].approvalPolicy, 'on-request');
  assert.deepEqual(sessionList.payload.sessions[0].permissions, { type: 'profile', id: ':workspace' });
  assert.equal(sessionList.payload.sessions[0].contextWindow, 123456);
  assert.equal(sessionList.payload.sessions[0].lastTokenUsage.totalTokens, 120);
  assert.equal(sessionList.payload.sessions[0].totalTokenUsage.totalTokens, 900);

  ws.send(JSON.stringify({ id: '2', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '2'));
  const sessionContent = messages.find((m) => m.type === 'response' && m.id === '2');
  assert.equal(sessionContent.ok, true);
  assert.equal(sessionContent.payload.session.session_id, sessionId);
  assert.equal(sessionContent.payload.session.approvalPolicy, 'on-request');
  assert.equal(sessionContent.payload.session.contextWindow, 123456);
  assert.equal(sessionContent.payload.session.lastTokenUsage.totalTokens, 120);
  assert.equal(sessionContent.payload.session.totalTokenUsage.totalTokens, 900);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge session.list includes stored sessions missing from backend page', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-session-list-store-test-'));
  const listedThread = {
    id: 'backend_thread_1',
    sessionId: 'backend_thread_1',
    forkedFromId: null,
    preview: 'Backend session',
    ephemeral: false,
    model: 'gpt-5',
    modelProvider: 'custom',
    createdAt: 1,
    updatedAt: 20,
    status: { type: 'idle' },
    path: null,
    cwd: '/tmp/backend-workspace',
    cliVersion: 'mock',
    source: 'app-server',
    threadSource: null,
    agentNickname: null,
    agentRole: null,
    gitInfo: null,
    name: 'Backend session',
    turns: [],
  };

  class PagedSessionBackend extends EventEmitter {
    async start() {}

    async stop() {}

    async listThreads() {
      return [structuredClone(listedThread)];
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'backend_thread_1',
    thread_id: 'backend_thread_1',
    title: 'Stale backend title',
    cwd: '/tmp/backend-workspace',
    model: 'gpt-5',
    backend: 'custom',
    preview: 'Stale backend preview',
    active: true,
    updatedAt: '2026-05-20T00:00:00.000Z',
  });
  await store.upsertSession({
    session_id: 'stored_only_thread_1',
    thread_id: 'stored_only_thread_1',
    title: 'Stored only session',
    cwd: '/tmp/stored-workspace',
    model: 'gpt-5',
    backend: 'custom',
    preview: 'Stored only session',
    active: true,
    updatedAt: '2026-05-21T00:00:00.000Z',
  });

  const bridge = new BridgeServer({
    backend: new PagedSessionBackend(),
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
  });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.list', payload: {} }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const sessionList = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(sessionList.ok, true);
  assert.deepEqual(
    sessionList.payload.sessions.map((session) => session.session_id).sort(),
    ['backend_thread_1', 'stored_only_thread_1'],
  );
  assert.deepEqual(
    new Set(sessionList.payload.sessions.map((session) => session.cwd)),
    new Set(['/tmp/backend-workspace', '/tmp/stored-workspace']),
  );
  assert.equal(
    sessionList.payload.sessions.find((session) => session.session_id === 'backend_thread_1').title,
    'Backend session',
  );

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('bridge backfills historical session metadata from local rollout without remote resume', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-local-metadata-test-'));
  const rolloutPath = path.join(dir, 'history-rollout.jsonl');
  const sessionId = 'local_metadata_thread_1';

  await fs.writeFile(rolloutPath, [
    JSON.stringify({
      timestamp: '2026-05-20T04:02:58.578Z',
      type: 'session_meta',
      payload: {
        id: sessionId,
        cwd: '/tmp/local-metadata-workspace',
        model: 'gpt-5.4',
      },
    }),
    JSON.stringify({
      timestamp: '2026-05-20T04:03:33.705Z',
      type: 'turn_context',
      payload: {
        turn_id: 'turn_local_metadata_1',
        cwd: '/tmp/local-metadata-workspace',
        approval_policy: 'on-request',
        sandbox_policy: { type: 'dangerFullAccess' },
        permission_profile: { type: 'disabled' },
        model: 'gpt-5.4',
        model_context_window: 950000,
        effort: 'xhigh',
      },
    }),
    JSON.stringify({
      timestamp: '2026-05-20T04:03:43.870Z',
      type: 'event_msg',
      payload: {
        type: 'token_count',
        info: {
          total_token_usage: {
            total_tokens: 14833,
            input_tokens: 14446,
            cached_input_tokens: 9600,
            output_tokens: 387,
            reasoning_output_tokens: 121,
          },
          last_token_usage: {
            total_tokens: 14833,
            input_tokens: 14446,
            cached_input_tokens: 9600,
            output_tokens: 387,
            reasoning_output_tokens: 121,
          },
          model_context_window: 950000,
        },
      },
    }),
    '',
  ].join('\n'), 'utf8');

  class NoRemoteMetadataBackend extends EventEmitter {
    constructor() {
      super();
      this.resumeCalls = 0;
      this.readCalls = 0;
    }

    async start() {}

    async stop() {}

    async listThreads() {
      return [{
        id: sessionId,
        sessionId,
        forkedFromId: null,
        preview: 'Local metadata session',
        ephemeral: false,
        modelProvider: 'custom',
        createdAt: 1,
        updatedAt: 2,
        status: { type: 'idle' },
        path: rolloutPath,
        cwd: '/tmp/local-metadata-workspace',
        cliVersion: 'mock',
        source: 'app-server',
        threadSource: null,
        agentNickname: null,
        agentRole: null,
        gitInfo: null,
        name: 'Local metadata session',
        turns: [],
      }];
    }

    async readThread() {
      this.readCalls += 1;
      return {
        thread: {
          id: sessionId,
          sessionId,
          forkedFromId: null,
          preview: 'Local metadata session',
          ephemeral: false,
          modelProvider: 'custom',
          createdAt: 1,
          updatedAt: 2,
          status: { type: 'idle' },
          path: rolloutPath,
          cwd: '/tmp/local-metadata-workspace',
          cliVersion: 'mock',
          source: 'app-server',
          threadSource: null,
          agentNickname: null,
          agentRole: null,
          gitInfo: null,
          name: 'Local metadata session',
          turns: [],
        },
      };
    }

    async resumeThread() {
      this.resumeCalls += 1;
      throw new Error('resume should not be needed for local metadata backfill');
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: sessionId,
    thread_id: sessionId,
    title: 'Local metadata session',
    cwd: '/tmp/local-metadata-workspace',
    model: '',
    backend: 'custom',
    preview: 'Local metadata session',
    active: true,
    thread: {
      id: sessionId,
      sessionId,
      path: rolloutPath,
      cwd: '/tmp/local-metadata-workspace',
      status: { type: 'idle' },
      turns: [],
    },
  });

  const backend = new NoRemoteMetadataBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  await once(ws, 'open');

  const messages = [];
  ws.on('message', (buffer) => {
    messages.push(JSON.parse(buffer.toString('utf8')));
  });

  ws.send(JSON.stringify({ id: '1', type: 'session.content', payload: { session_id: sessionId } }));
  await waitFor(() => messages.find((m) => m.type === 'response' && m.id === '1'));
  const contentResponse = messages.find((m) => m.type === 'response' && m.id === '1');
  assert.equal(contentResponse.ok, true);
  assert.equal(contentResponse.payload.session.model, 'gpt-5.4');
  assert.equal(contentResponse.payload.session.reasoningEffort, 'xhigh');
  assert.equal(contentResponse.payload.session.approvalPolicy, 'on-request');
  assert.deepEqual(contentResponse.payload.session.sandbox, { type: 'dangerFullAccess' });
  assert.equal(contentResponse.payload.session.contextWindow, 950000);
  assert.deepEqual(contentResponse.payload.session.lastTokenUsage, {
    totalTokens: 14833,
    inputTokens: 14446,
    cachedInputTokens: 9600,
    outputTokens: 387,
    reasoningOutputTokens: 121,
  });
  assert.deepEqual(contentResponse.payload.session.totalTokenUsage, {
    totalTokens: 14833,
    inputTokens: 14446,
    cachedInputTokens: 9600,
    outputTokens: 387,
    reasoningOutputTokens: 121,
  });
  assert.equal(backend.resumeCalls, 0);

  ws.close();
  await once(ws, 'close');
  await bridge.stop();
  await fs.rm(dir, { recursive: true, force: true });
});

test('approval response uses original backend request id type', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bridge-approval-id-test-'));

  class ApprovalIdBackend extends EventEmitter {
    constructor() {
      super();
      this.responses = [];
    }

    async start() {}

    async stop() {}

    async respondRequest(requestId, result, error = null) {
      this.responses.push({ requestId, result, error });
    }
  }

  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  await store.upsertSession({
    session_id: 'approval_thread_1',
    thread_id: 'approval_thread_1',
    title: 'Approval id session',
    cwd: '/tmp/approval-id-workspace',
    model: 'gpt-5',
    backend: 'custom',
    preview: '',
    active: true,
  });

  const backend = new ApprovalIdBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token' });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=test-token`);
  try {
    await once(ws, 'open');

    const messages = [];
    ws.on('message', (buffer) => {
      messages.push(JSON.parse(buffer.toString('utf8')));
    });

    backend.emit('message', {
      type: 'request',
      method: 'item/commandExecution/requestApproval',
      id: 42,
      params: {
        threadId: 'approval_thread_1',
        turnId: 'approval_turn_1',
        itemId: 'approval_item_1',
        command: 'echo hello',
        availableDecisions: ['accept', 'decline'],
      },
    });

    await waitFor(() => messages.find((m) => m.type === 'event' && m.event === 'item/commandExecution/requestApproval'));
    const approvalEvent = messages.find((m) => m.type === 'event' && m.event === 'item/commandExecution/requestApproval');
    assert.equal(approvalEvent.request_id, '42');

    ws.send(JSON.stringify({
      id: 'client-approval-response',
      type: 'approval.response',
      payload: {
        session_id: 'approval_thread_1',
        request_id: approvalEvent.request_id,
        decision: 'accept',
      },
    }));

    await waitFor(() => backend.responses.length === 1);
    assert.equal(backend.responses[0].requestId, 42);
    assert.deepEqual(backend.responses[0].result, { decision: 'accept' });
    assert.equal(backend.responses[0].error, null);
  } finally {
    if (ws.readyState === WebSocket.OPEN) {
      ws.close();
      await once(ws, 'close');
    }
    await bridge.stop();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

async function waitFor(predicate, timeoutMs = 8000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error('timed out');
}
