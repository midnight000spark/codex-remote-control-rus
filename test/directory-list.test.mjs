import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { MockBackend } from '../src/backends/mock.mjs';
import { BridgeServer } from '../src/bridge-server.mjs';
import { StateStore } from '../src/store.mjs';

test('bridge directory.list returns remote child directories only', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-directory-list-test-'));
  const workspaceDir = path.join(tempDir, 'workspace');
  await fs.mkdir(path.join(workspaceDir, 'project-alpha'), { recursive: true });
  await fs.mkdir(path.join(workspaceDir, 'project-beta'));
  await fs.symlink(path.join(workspaceDir, 'project-alpha'), path.join(workspaceDir, 'project-link'));
  await fs.symlink(path.join(workspaceDir, 'missing'), path.join(workspaceDir, 'broken-link'));
  await fs.writeFile(path.join(workspaceDir, 'notes.txt'), 'not a directory', 'utf8');

  const store = new StateStore(path.join(tempDir, 'state.json'));
  const bridge = new BridgeServer({
    backend: new MockBackend(),
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
  });
  await bridge.start();
  const ws = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/ws?token=test-token`);
  const messages = [];
  ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));

  try {
    await once(ws, 'open');
    ws.send(JSON.stringify({ id: 'directories', type: 'directory.list', payload: { path: workspaceDir } }));
    await waitFor(() => messages.find((message) => message.id === 'directories'));

    const response = messages.find((message) => message.id === 'directories');
    assert.equal(response.ok, true);
    assert.equal(response.payload.path, workspaceDir);
    assert.equal(response.payload.parent_path, tempDir);
    assert.deepEqual(
      response.payload.directories.map((entry) => entry.name),
      ['project-alpha', 'project-beta', 'project-link'],
    );
    assert.equal(response.payload.directories[0].path, path.join(workspaceDir, 'project-alpha'));
    assert.equal(response.payload.truncated, false);
  } finally {
    ws.close();
    await once(ws, 'close');
    await bridge.stop();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test('bridge directory.list caps large directory responses', async () => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-directory-limit-test-'));
  const workspaceDir = path.join(tempDir, 'workspace');
  await fs.mkdir(workspaceDir, { recursive: true });
  await Promise.all(
    Array.from({ length: 520 }, (_, index) => (
      fs.mkdir(path.join(workspaceDir, `dir-${String(index).padStart(3, '0')}`))
    )),
  );

  const store = new StateStore(path.join(tempDir, 'state.json'));
  const bridge = new BridgeServer({
    backend: new MockBackend(),
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
  });
  await bridge.start();
  const ws = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/ws?token=test-token`);
  const messages = [];
  ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));

  try {
    await once(ws, 'open');
    ws.send(JSON.stringify({ id: 'directories', type: 'directory.list', payload: { path: workspaceDir } }));
    await waitFor(() => messages.find((message) => message.id === 'directories'));

    const response = messages.find((message) => message.id === 'directories');
    assert.equal(response.ok, true);
    assert.equal(response.payload.directories.length, 500);
    assert.equal(response.payload.directories[0].name, 'dir-000');
    assert.equal(response.payload.directories[499].name, 'dir-499');
    assert.equal(response.payload.truncated, true);
  } finally {
    ws.close();
    await once(ws, 'close');
    await bridge.stop();
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

async function waitFor(predicate, timeoutMs = 3000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    const value = predicate();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error('timed out waiting for condition');
}
