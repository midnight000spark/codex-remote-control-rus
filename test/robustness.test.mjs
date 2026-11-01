import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { BridgeServer } from '../src/bridge-server.mjs';
import { StateStore } from '../src/store.mjs';
import { MockBackend } from '../src/backends/mock.mjs';
import WebSocket from 'ws';
import { sweepSessionUploads } from '../src/bridge/file-reader.mjs';

async function createHarness(options = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-robustness-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  const backend = new MockBackend();
  const bridge = new BridgeServer({ backend, store, host: '127.0.0.1', port: 0, token: 'test-token', ...options });
  await bridge.start();
  const cleanup = async () => {
    await bridge.stop().catch(() => {});
    await fs.rm(dir, { recursive: true, force: true });
  };
  return { dir, store, bridge, cleanup };
}

function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(predicate, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await wait(20);
  }
  throw new Error('timed out waiting for condition');
}

async function withTimeout(promise, timeoutMs = 2000) {
  let timer = null;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`timed out after ${timeoutMs} ms`)), timeoutMs);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

test('uploads sweep deletes only old files that no session references', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-uploads-test-'));
  const uploadDir = path.join(dir, 'uploads');
  await fs.mkdir(uploadDir, { recursive: true });
  const store = new StateStore(path.join(dir, 'state.json'), { saveDebounceMs: 5 });
  await store.load();

  const old = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
  const referencedPath = path.join(uploadDir, 'referenced.png');
  const orphanPath = path.join(uploadDir, 'orphan.png');
  const freshPath = path.join(uploadDir, 'fresh.png');
  for (const file of [referencedPath, orphanPath, freshPath]) {
    await fs.writeFile(file, 'bytes');
  }
  await fs.utimes(referencedPath, old, old);
  await fs.utimes(orphanPath, old, old);

  try {
    await store.upsertSession({
      session_id: 'session_1',
      thread_id: 'session_1',
      title: 'upload',
      cwd: dir,
      model: 'mock-gpt-5',
      backend: 'mock',
      preview: '',
      active: true,
    });
    await store.appendEvent('session_1', {
      type: 'event',
      event: 'turn/input',
      session_id: 'session_1',
      payload: { text: 'look', input: [{ type: 'localImage', path: referencedPath }] },
    });

    const result = await sweepSessionUploads(store, { maxAgeMs: 7 * 24 * 60 * 60 * 1000 });
    assert.deepEqual(result, { removed: 1, kept: 2 });
    assert.equal(await fs.stat(referencedPath).then(() => true), true, 'referenced image is kept');
    assert.equal(await fs.stat(freshPath).then(() => true), true, 'fresh image is kept');
    await assert.rejects(() => fs.stat(orphanPath), /ENOENT/);
  } finally {
    await store.flush();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('uploads sweep keeps everything when the uploads directory is missing', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-uploads-empty-test-'));
  const store = new StateStore(path.join(dir, 'state.json'));
  try {
    assert.deepEqual(await sweepSessionUploads(store), { removed: 0, kept: 0 });
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('broadcast closes a connection whose buffer is over the limit', async () => {
  const { bridge, cleanup } = await createHarness();
  try {
    const closed = [];
    const slow = {
      readyState: 1,
      bufferedAmount: 5 * 1024 * 1024,
      remoteAddress: 'slow',
      close: (code, reason) => closed.push([code, reason]),
      terminate: () => {},
    };
    const healthy = {
      readyState: 1,
      bufferedAmount: 0,
      remoteAddress: 'healthy',
      sent: [],
      close: () => {},
      terminate: () => {},
      send: (data) => healthy.sent.push(data),
    };
    bridge.connections.add(slow);
    bridge.connections.add(healthy);

    await bridge.backendListener({ type: 'notification', method: 'turn/started', params: {} });

    assert.deepEqual(closed, [[1009, 'client too slow']]);
    assert.equal(bridge.connections.has(slow), false);
    assert.equal(healthy.sent.length, 1);
  } finally {
    await cleanup();
  }
});

test('dropping the last client stops the monitor without waiting for the close handshake', async () => {
  const { bridge, cleanup } = await createHarness();
  try {
    // A peer that never answers the close frame: the socket must not keep the
    // external monitor polling until closeTimeout expires.
    let closeArgs = null;
    const mute = {
      readyState: 1,
      bufferedAmount: 5 * 1024 * 1024,
      remoteAddress: 'mute',
      close: (code, reason) => {
        closeArgs = [code, reason];
      },
      terminate: () => {},
    };
    bridge.externalSessionMonitor.start();
    bridge.connections.add(mute);

    await bridge.backendListener({ type: 'notification', method: 'turn/started', params: {} });

    assert.deepEqual(closeArgs, [1009, 'client too slow']);
    assert.equal(bridge.connections.size, 0);
    assert.equal(bridge.externalSessionMonitor.running, false);
  } finally {
    await cleanup();
  }
});

test('a large request response is dropped instead of buffered', async () => {
  const { bridge, cleanup } = await createHarness();
  let client = null;
  try {
    client = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/?token=test-token`);
    await withTimeout(new Promise((resolve, reject) => {
      client.once('open', resolve);
      client.once('error', reject);
    }));
    await waitFor(() => bridge.connections.size === 1);

    // Everything below 4 MiB is fine; this socket is already at the limit, so
    // the next frame - a plain session.list response - must not be queued.
    const serverSocket = [...bridge.connections][0];
    Object.defineProperty(serverSocket, 'bufferedAmount', { value: 4 * 1024 * 1024, configurable: true });

    const closed = new Promise((resolve) => {
      client.once('close', (code, reason) => resolve({ code, reason: reason.toString() }));
    });
    client.send(JSON.stringify({ id: 'bp-1', type: 'session.list', payload: {} }));

    const { code, reason } = await withTimeout(closed);
    assert.equal(code, 1009);
    assert.equal(reason, 'client too slow');
    assert.equal(bridge.connections.size, 0);
    assert.equal(bridge.externalSessionMonitor.running, false);
  } finally {
    client?.terminate();
    await cleanup();
  }
});

test('a dropped socket is terminated when the peer ignores the close frame', async () => {
  const { bridge, cleanup } = await createHarness({ dropCloseTimeoutMs: 20 });
  try {
    let terminated = 0;
    const mute = {
      readyState: 1,
      bufferedAmount: 5 * 1024 * 1024,
      remoteAddress: 'mute',
      close: () => {},
      terminate: () => {
        terminated += 1;
      },
    };
    bridge.connections.add(mute);

    await bridge.backendListener({ type: 'notification', method: 'turn/started', params: {} });
    assert.equal(terminated, 0, 'the close handshake is given a chance first');

    await wait(80);
    assert.equal(terminated, 1);
  } finally {
    await cleanup();
  }
});

test('heartbeat terminates a connection that stopped answering pings', async () => {
  const { bridge, cleanup } = await createHarness({ heartbeatIntervalMs: 1000 });
  try {
    let terminated = 0;
    let pinged = 0;
    const dead = {
      // isAlive === false is what the previous heartbeat tick leaves behind
      // when the pong never arrives.
      isAlive: false,
      readyState: 1,
      bufferedAmount: 0,
      remoteAddress: 'dead',
      ping: () => {
        pinged += 1;
      },
      terminate: () => {
        terminated += 1;
      },
      close: () => {},
    };
    bridge.connections.add(dead);

    await wait(1500);

    assert.equal(pinged, 0, 'a dead connection is terminated instead of pinged again');
    assert.equal(terminated, 1);
    assert.equal(bridge.connections.has(dead), false);
  } finally {
    await cleanup();
  }
});

test('heartbeat pings a live connection on the first tick', async () => {
  const { bridge, cleanup } = await createHarness({ heartbeatIntervalMs: 1000 });
  try {
    let terminated = 0;
    let pinged = 0;
    const alive = {
      isAlive: true,
      readyState: 1,
      bufferedAmount: 0,
      remoteAddress: 'alive',
      ping: () => {
        pinged += 1;
      },
      terminate: () => {
        terminated += 1;
      },
      close: () => {},
    };
    bridge.connections.add(alive);

    await wait(1500);

    assert.equal(pinged, 1);
    assert.equal(terminated, 0);
    assert.equal(bridge.connections.has(alive), true);
    // Clean up before the interval can fire again.
    bridge.connections.delete(alive);
  } finally {
    await cleanup();
  }
});