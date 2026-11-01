// Security regression tests for the bridge. These encode the intended boundary:
// no unauthenticated data access, no filesystem escape, no blind approval forwarding,
// and no crashable WebSocket connections.

import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { WebSocket } from 'ws';
import { StateStore } from '../src/store.mjs';
import { BridgeServer } from '../src/bridge-server.mjs';
import { MockBackend } from '../src/backends/mock.mjs';

const TOKEN = 'test-token';

async function createHarness({ backend = new MockBackend(), session = null } = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-security-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  await store.load();
  if (session) {
    await store.upsertSession(session);
  }
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: TOKEN });
  await bridge.start();
  const { port } = bridge.address();

  const connect = async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${TOKEN}`);
    await once(ws, 'open');
    const messages = [];
    ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));
    return { ws, messages };
  };

  const cleanup = async () => {
    await bridge.stop();
    await fs.rm(dir, { recursive: true, force: true });
  };

  return { dir, store, bridge, port, connect, cleanup };
}

async function waitFor(predicate, timeoutMs = 5000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error('timed out');
}

test('GET /v1/sessions must not expose sessions without a token', async () => {
  const harness = await createHarness({
    session: { session_id: 'private_session', title: 'secret', cwd: '/tmp', backend: 'mock' },
  });
  try {
    const response = await fetch(`http://127.0.0.1:${harness.port}/v1/sessions`);
    assert.ok(
      [401, 404].includes(response.status),
      `expected 401/404, got ${response.status}`,
    );
    const body = await response.text();
    assert.equal(body.includes('private_session'), false);
  } finally {
    await harness.cleanup();
  }
});

test('HTTP /v1/file rejects traversal outside the session cwd', async () => {
  const harness = await createHarness();
  try {
    const workspace = path.join(harness.dir, 'workspace');
    await fs.mkdir(workspace, { recursive: true });
    await fs.writeFile(path.join(workspace, 'inside.txt'), 'inside', 'utf8');
    await fs.writeFile(path.join(harness.dir, 'secret.txt'), 'top secret', 'utf8');
    await harness.store.upsertSession({
      session_id: 'containment_session',
      cwd: workspace,
      thread: { id: 'containment_session', cwd: workspace, status: { type: 'idle' }, turns: [] },
      backend: 'mock',
    });

    const response = await fetch(
      `http://127.0.0.1:${harness.port}/v1/file?session_id=containment_session&path=${encodeURIComponent('../secret.txt')}&token=${TOKEN}`,
    );
    assert.notEqual(response.status, 200, 'traversal must not return file contents');
  } finally {
    await harness.cleanup();
  }
});

test('WS file.read rejects absolute paths outside the session cwd', async () => {
  const harness = await createHarness();
  try {
    const workspace = path.join(harness.dir, 'workspace');
    await fs.mkdir(workspace, { recursive: true });
    const outside = path.join(harness.dir, 'outside.txt');
    await fs.writeFile(outside, 'top secret', 'utf8');
    await harness.store.upsertSession({
      session_id: 'abs_session',
      cwd: workspace,
      thread: { id: 'abs_session', cwd: workspace, status: { type: 'idle' }, turns: [] },
      backend: 'mock',
    });

    const { ws, messages } = await harness.connect();
    ws.send(JSON.stringify({
      id: '1',
      type: 'file.read',
      payload: { session_id: 'abs_session', path: outside },
    }));
    await waitFor(() => messages.find((message) => message.type === 'response' && message.id === '1'));
    const response = messages.find((message) => message.type === 'response' && message.id === '1');
    assert.equal(response.ok, false, 'out-of-cwd absolute read must be rejected');
    ws.close();
    await once(ws, 'close');
  } finally {
    await harness.cleanup();
  }
});

test('approval.response for an unknown request id is not forwarded to the backend', async () => {
  class RecordingBackend extends EventEmitter {
    constructor() {
      super();
      this.responded = [];
    }
    async start() {}
    async stop() {}
    async readThread() {
      return null;
    }
    async respondRequest(requestId, result, error) {
      this.responded.push({ requestId, result, error });
    }
  }

  const backend = new RecordingBackend();
  const harness = await createHarness({ backend });
  try {
    await harness.store.upsertSession({
      session_id: 'approval_session',
      cwd: '/tmp',
      thread: { id: 'approval_session', cwd: '/tmp', status: { type: 'idle' }, turns: [] },
      backend: 'mock',
    });
    const { ws } = await harness.connect();
    ws.send(JSON.stringify({
      id: '1',
      type: 'approval.response',
      payload: { session_id: 'approval_session', request_id: 'unknown_id', decision: 'approved' },
    }));
    await new Promise((resolve) => setTimeout(resolve, 200));
    assert.deepEqual(backend.responded, [], 'unmatched approval must never reach the backend');
    ws.close();
    await once(ws, 'close');
  } finally {
    await harness.cleanup();
  }
});

test('every accepted WebSocket connection has an error listener', async () => {
  const harness = await createHarness();
  try {
    const { ws } = await harness.connect();
    await waitFor(() => harness.bridge.connections.size === 1);
    const [serverSocket] = harness.bridge.connections;
    assert.ok(
      serverSocket.listenerCount('error') >= 1,
      'missing error listener would make a malformed frame crash the bridge',
    );
    ws.close();
    await once(ws, 'close');
  } finally {
    await harness.cleanup();
  }
});