// The wire format is documented in docs/protocol.md: identifiers use
// snake_case, settings use camelCase. These tests pin the tolerance the bridge
// adds on top of that: a setting sent in the other spelling must still land,
// otherwise a client that follows the document loses its settings silently.

import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';
import { StateStore } from '../src/store.mjs';
import { BridgeServer } from '../src/bridge-server.mjs';
import { MockBackend } from '../src/backends/mock.mjs';

const TOKEN = 'test-token';

class RecordingBackend extends MockBackend {
  constructor() {
    super();
    this.startTurnCalls = [];
  }

  async startTurn(threadId, params = {}) {
    this.startTurnCalls.push({ threadId, params });
    return super.startTurn(threadId, params);
  }
}

async function createHarness() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-aliases-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  const backend = new RecordingBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: TOKEN });
  await bridge.start();
  const { port } = bridge.address();

  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${TOKEN}`);
  await once(ws, 'open');
  const messages = [];
  ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));

  let requestSeq = 0;
  const request = async (type, payload) => {
    const id = `alias-${++requestSeq}`;
    ws.send(JSON.stringify({ id, type, payload }));
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      const found = messages.find((message) => message.id === id);
      if (found) return found;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error(`no response for ${type}`);
  };

  const cleanup = async () => {
    ws.terminate();
    await bridge.stop();
    await fs.rm(dir, { recursive: true, force: true });
  };

  await store.upsertSession({
    session_id: 'alias_session',
    thread_id: 'alias_session',
    title: 'alias',
    cwd: dir,
    backend: 'mock',
    active: true,
  });

  return { dir, store, backend, bridge, request, cleanup };
}

test('snake_case settings are aliased to the camelCase keys ingress reads', async () => {
  const harness = await createHarness();
  try {
    const response = await harness.request('session.update', {
      session_id: 'alias_session',
      approval_policy: 'never',
      sandbox_policy: 'read-only',
    });
    assert.equal(response.ok, true, JSON.stringify(response));

    const session = harness.store.getSession('alias_session');
    assert.equal(session.approvalPolicy, 'never');
    assert.equal(session.sandbox?.type, 'readOnly');
  } finally {
    await harness.cleanup();
  }
});

test('an explicit camelCase setting wins over its snake_case alias', async () => {
  const harness = await createHarness();
  try {
    const response = await harness.request('session.update', {
      session_id: 'alias_session',
      approval_policy: 'never',
      approvalPolicy: 'on-request',
    });
    assert.equal(response.ok, true, JSON.stringify(response));
    assert.equal(harness.store.getSession('alias_session').approvalPolicy, 'on-request');
  } finally {
    await harness.cleanup();
  }
});

test('snake_case turn settings reach the backend', async () => {
  const harness = await createHarness();
  try {
    const started = await harness.request('session.start', { cwd: harness.dir });
    assert.equal(started.ok, true, JSON.stringify(started));
    const sessionId = started.payload.session.session_id;

    const response = await harness.request('turn.send', {
      session_id: sessionId,
      text: 'hello',
      approval_policy: 'never',
      sandbox_policy: 'read-only',
    });
    assert.equal(response.ok, true, JSON.stringify(response));

    assert.equal(harness.backend.startTurnCalls.length, 1);
    const { params } = harness.backend.startTurnCalls[0];
    assert.equal(params.approvalPolicy, 'never');
    assert.equal(params.sandboxPolicy?.type, 'readOnly');
  } finally {
    await harness.cleanup();
  }
});

test('identifiers are not rewritten by the alias pass', async () => {
  const harness = await createHarness();
  try {
    // Nothing here may be mistaken for a setting: the session still resolves.
    const response = await harness.request('session.update', {
      thread_id: 'alias_session',
      approvalPolicy: 'never',
    });
    assert.equal(response.ok, true, JSON.stringify(response));
    assert.equal(harness.store.getSession('alias_session').approvalPolicy, 'never');
  } finally {
    await harness.cleanup();
  }
});