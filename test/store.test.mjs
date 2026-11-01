import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/store.mjs';

async function createStore() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-store-test-'));
  const store = new StateStore(path.join(dir, 'state.json'), { saveDebounceMs: 5 });
  await store.load();
  const cleanup = async () => {
    await store.flush();
    await fs.rm(dir, { recursive: true, force: true });
  };
  return { store, cleanup };
}

const session = (overrides = {}) => ({
  session_id: 'session_1',
  thread_id: 'session_1',
  title: 'first',
  cwd: '/tmp',
  model: 'mock-gpt-5',
  backend: 'mock',
  preview: '',
  active: true,
  ...overrides,
});

test('repeated session reads reuse one snapshot until the store is mutated', async () => {
  const { store, cleanup } = await createStore();
  try {
    await store.upsertSession(session());

    const first = store.getSession('session_1');
    const second = store.getSession('session_1');
    assert.equal(first, second, 'reads without a mutation must not re-clone the session');

    await store.upsertSession(session({ title: 'second' }));
    const afterWrite = store.getSession('session_1');
    assert.notEqual(afterWrite, first, 'a mutation must invalidate the cached snapshot');
    assert.equal(afterWrite.title, 'second');
  } finally {
    await cleanup();
  }
});

test('appended events invalidate the cached session snapshot', async () => {
  const { store, cleanup } = await createStore();
  try {
    await store.upsertSession(session());
    const before = store.getSession('session_1');
    await store.appendEvent('session_1', { type: 'event', event: 'turn/input', payload: { text: 'hi' } });

    const after = store.getSession('session_1');
    assert.notEqual(after, before);
    assert.equal(after.events.length, 1);
    assert.equal(store.syncEvents('session_1', 0).length, 1);
  } finally {
    await cleanup();
  }
});

test('returned snapshots are frozen so callers cannot corrupt stored state', async () => {
  const { store, cleanup } = await createStore();
  try {
    await store.upsertSession(session());
    const snapshot = store.getSession('session_1');

    assert.ok(Object.isFrozen(snapshot));
    assert.throws(() => {
      snapshot.title = 'mutated';
    }, TypeError);

    assert.equal(store.getSession('session_1').title, 'first');
  } finally {
    await cleanup();
  }
});