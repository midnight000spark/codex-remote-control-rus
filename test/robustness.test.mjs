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

test('uploads sweep keeps a file referenced through a relative path', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-uploads-relative-test-'));
  const uploadDir = path.join(dir, 'uploads');
  await fs.mkdir(uploadDir, { recursive: true });
  const store = new StateStore(path.join(dir, 'state.json'), { saveDebounceMs: 5 });
  await store.load();
  const old = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
  const imagePath = path.join(uploadDir, 'relative.png');
  await fs.writeFile(imagePath, 'bytes');
  await fs.utimes(imagePath, old, old);
  try {
    await store.upsertSession({
      session_id: 'session_relative',
      thread_id: 'session_relative',
      title: 'relative',
      cwd: dir,
      backend: 'mock',
      active: true,
    });
    // Same file, spelled relative to the workspace instead of absolute.
    await store.appendEvent('session_relative', {
      type: 'event',
      event: 'turn/input',
      session_id: 'session_relative',
      payload: { text: 'look', input: [{ type: 'localImage', path: 'uploads/relative.png' }] },
    });

    const result = await sweepSessionUploads(store, { maxAgeMs: 7 * 24 * 60 * 60 * 1000 });
    assert.deepEqual(result, { removed: 0, kept: 1 });
    assert.equal(await fs.stat(imagePath).then(() => true), true);
  } finally {
    await store.flush();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('uploads sweep does not follow symlinks out of the uploads directory', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-uploads-symlink-test-'));
  const uploadDir = path.join(dir, 'uploads');
  await fs.mkdir(uploadDir, { recursive: true });
  const store = new StateStore(path.join(dir, 'state.json'), { saveDebounceMs: 5 });
  await store.load();
  const old = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
  const targetPath = path.join(dir, 'keep-me.png');
  const linkPath = path.join(uploadDir, 'stale-link.png');
  await fs.writeFile(targetPath, 'outside');
  await fs.symlink(targetPath, linkPath);
  await fs.lutimes(linkPath, old, old);
  try {
    const result = await sweepSessionUploads(store, { maxAgeMs: 7 * 24 * 60 * 60 * 1000 });
    assert.deepEqual(result, { removed: 1, kept: 0 });
    await assert.rejects(() => fs.lstat(linkPath), /ENOENT/, 'the link itself is removed');
    assert.equal(await fs.stat(targetPath).then(() => true), true, 'the target is untouched');
  } finally {
    await store.flush();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('uploads sweep keeps a file that sits exactly on the TTL boundary', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-uploads-ttl-test-'));
  const uploadDir = path.join(dir, 'uploads');
  await fs.mkdir(uploadDir, { recursive: true });
  const store = new StateStore(path.join(dir, 'state.json'), { saveDebounceMs: 5 });
  await store.load();
  const filePath = path.join(uploadDir, 'boundary.png');
  await fs.writeFile(filePath, 'bytes');
  const maxAgeMs = 7 * 24 * 60 * 60 * 1000;
  const old = new Date(Date.now() - maxAgeMs);
  await fs.utimes(filePath, old, old);
  try {
    const stat = await fs.stat(filePath);
    const result = await sweepSessionUploads(store, { maxAgeMs, now: stat.mtimeMs + maxAgeMs });
    assert.deepEqual(result, { removed: 0, kept: 1 });
    assert.equal(await fs.stat(filePath).then(() => true), true);
  } finally {
    await store.flush();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('uploads sweep scans sessions without cloning them', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-uploads-scan-test-'));
  const uploadDir = path.join(dir, 'uploads');
  await fs.mkdir(uploadDir, { recursive: true });
  const store = new StateStore(path.join(dir, 'state.json'), { saveDebounceMs: 5 });
  await store.load();
  const old = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);
  const orphanPath = path.join(uploadDir, 'orphan.png');
  await fs.writeFile(orphanPath, 'bytes');
  await fs.utimes(orphanPath, old, old);
  // A deep clone + freeze of every session on the event loop is what this
  // sweep must not do.
  store.listSessions = () => {
    throw new Error('sweep must not use listSessions()');
  };
  try {
    const result = await sweepSessionUploads(store, { maxAgeMs: 7 * 24 * 60 * 60 * 1000 });
    assert.deepEqual(result, { removed: 1, kept: 0 });
  } finally {
    await store.flush();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('uploads sweep counts every directory entry once', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-uploads-count-test-'));
  const uploadDir = path.join(dir, 'uploads');
  await fs.mkdir(path.join(uploadDir, 'nested'), { recursive: true });
  const store = new StateStore(path.join(dir, 'state.json'), { saveDebounceMs: 5 });
  await store.load();
  try {
    const result = await sweepSessionUploads(store, { maxAgeMs: 7 * 24 * 60 * 60 * 1000 });
    assert.deepEqual(result, { removed: 0, kept: 1 }, 'a subdirectory is kept, not ignored');
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

test('a frame larger than the limit is still delivered to an idle socket', async () => {
  const { bridge, cleanup } = await createHarness();
  try {
    let closed = 0;
    const idle = {
      readyState: 1,
      // Nothing is queued yet, so the frame itself must not be refused just
      // because it is bigger than the limit.
      bufferedAmount: 0,
      remoteAddress: 'idle',
      sent: [],
      send: (data) => idle.sent.push(data),
      close: () => {
        closed += 1;
      },
      terminate: () => {},
    };
    bridge.connections.add(idle);

    await bridge.backendListener({
      type: 'notification',
      method: 'turn/started',
      params: { big: 'x'.repeat(5 * 1024 * 1024) },
    });

    assert.equal(closed, 0);
    assert.equal(bridge.connections.has(idle), true);
    assert.equal(idle.sent.length, 1);
    assert.ok(Buffer.byteLength(idle.sent[0]) > 4 * 1024 * 1024);
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

test('the bridge reports the package version', async () => {
  const pkg = JSON.parse(await fs.readFile(new URL('../package.json', import.meta.url), 'utf8'));
  const { bridge, cleanup } = await createHarness();
  let client = null;
  try {
    const health = await fetch(`http://127.0.0.1:${bridge.address().port}/healthz`);
    assert.equal(health.status, 200);
    assert.equal((await health.json()).version, pkg.version);

    client = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/?token=test-token`);
    const hello = await withTimeout(new Promise((resolve, reject) => {
      client.once('message', (buffer) => resolve(JSON.parse(buffer.toString('utf8'))));
      client.once('error', reject);
    }));
    assert.equal(hello.type, 'hello');
    assert.equal(hello.payload.version, pkg.version);
  } finally {
    client?.terminate();
    await cleanup();
  }
});

test('a pong marks the connection alive again', async () => {
  const { bridge, cleanup } = await createHarness();
  let client = null;
  try {
    client = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/?token=test-token`);
    await withTimeout(new Promise((resolve, reject) => {
      client.once('open', resolve);
      client.once('error', reject);
    }));
    await waitFor(() => bridge.connections.size === 1);

    // isAlive === false is what a heartbeat tick leaves behind. If the pong
    // listener is missing, nothing sets it back and the client is killed on the
    // next tick.
    const [serverSocket] = bridge.connections;
    serverSocket.isAlive = false;
    serverSocket.emit('pong');
    assert.equal(serverSocket.isAlive, true);
  } finally {
    client?.terminate();
    await cleanup();
  }
});

test('the heartbeat keeps a responsive client and drops a silent one', async () => {
  const { bridge, cleanup } = await createHarness({ heartbeatIntervalMs: 1000 });
  const clients = [];
  try {
    const url = `ws://127.0.0.1:${bridge.address().port}/?token=test-token`;
    const responsive = new WebSocket(url);
    // A peer that completes the handshake but never answers a ping.
    const silent = new WebSocket(url, { autoPong: false });
    clients.push(responsive, silent);
    await withTimeout(Promise.all([
      new Promise((resolve, reject) => {
        responsive.once('open', resolve);
        responsive.once('error', reject);
      }),
      new Promise((resolve, reject) => {
        silent.once('open', resolve);
        silent.once('error', reject);
      }),
    ]));
    await waitFor(() => bridge.connections.size === 2);

    await waitFor(() => bridge.connections.size === 1, 5000);

    assert.equal(responsive.readyState, WebSocket.OPEN, 'the client that answers pings stays');
    assert.notEqual(silent.readyState, WebSocket.OPEN, 'the silent client is dropped');
  } finally {
    for (const client of clients) client.terminate();
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