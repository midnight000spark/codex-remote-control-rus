import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { EventEmitter, once } from 'node:events';
import WebSocket from 'ws';
import { BridgeServer } from '../src/bridge-server.mjs';
import { externalMonitorBackoffMs } from '../src/bridge/external-session-monitor.mjs';
import {
  createRolloutEventCursor,
  readRolloutEventsFromCursor,
} from '../src/bridge/rollout-history.mjs';
import { StateStore } from '../src/store.mjs';

function rolloutLines(turnId, userText, assistantText, second) {
  return [
    JSON.stringify({ timestamp: `2026-08-03T00:00:${second}.000Z`, type: 'event_msg', payload: { type: 'task_started', turn_id: turnId } }),
    JSON.stringify({ timestamp: `2026-08-03T00:00:${second}.100Z`, type: 'event_msg', payload: { type: 'user_message', message: userText } }),
    JSON.stringify({ timestamp: `2026-08-03T00:00:${second}.200Z`, type: 'event_msg', payload: { type: 'agent_message', message: assistantText } }),
    JSON.stringify({ timestamp: `2026-08-03T00:00:${second}.300Z`, type: 'event_msg', payload: { type: 'task_complete', turn_id: turnId } }),
    '',
  ].join('\n');
}

class ExternalThreadBackend extends EventEmitter {
  constructor(thread) {
    super();
    this.thread = thread;
    this.listCalls = 0;
  }

  async start() {}

  async stop() {}

  async listThreads() {
    this.listCalls += 1;
    return [structuredClone(this.thread)];
  }

  async readThread() {
    return { thread: structuredClone(this.thread) };
  }
}

test('incremental rollout cursor preserves turn context across a large prefix', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-rollout-cursor-test-'));
  const rolloutPath = path.join(dir, 'rollout.jsonl');
  const noise = Array.from({ length: 4000 }, (_, index) => JSON.stringify({
    timestamp: `2026-08-03T00:00:01.${String(index % 1000).padStart(3, '0')}Z`,
    type: 'event_msg',
    payload: { type: 'token_count', padding: 'x'.repeat(120) },
  }));
  const baselineLines = [
    JSON.stringify({ timestamp: '2026-08-03T00:00:00.000Z', type: 'event_msg', payload: { type: 'task_started', turn_id: 'turn_large' } }),
    JSON.stringify({ timestamp: '2026-08-03T00:00:00.100Z', type: 'event_msg', payload: { type: 'agent_message', message: '第一段' } }),
    ...noise,
    '',
  ].join('\n');

  try {
    await fs.writeFile(rolloutPath, baselineLines, 'utf8');
    const baselineSize = (await fs.stat(rolloutPath)).size;
    const cursor = await createRolloutEventCursor(rolloutPath, baselineSize);
    await fs.appendFile(rolloutPath, [
      JSON.stringify({ timestamp: '2026-08-03T00:00:02.000Z', type: 'event_msg', payload: { type: 'agent_message', message: '第二段' } }),
      JSON.stringify({ timestamp: '2026-08-03T00:00:02.100Z', type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn_large' } }),
      '',
    ].join('\n'), 'utf8');

    const result = await readRolloutEventsFromCursor(
      'large_thread',
      rolloutPath,
      'large_thread',
      cursor,
    );
    assert.equal(result.events.length, 2);
    assert.equal(result.events[0].event, 'item/completed');
    assert.equal(result.events[0].turn_id, 'turn_large');
    assert.equal(result.events[0].payload.item.id, 'rollout_turn_large_0002');
    assert.equal(result.events[0].payload.item.text, '第二段');
    assert.equal(result.events[1].event, 'turn/completed');
    assert.equal(result.cursor.offset, (await fs.stat(rolloutPath)).size);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('incremental rollout cursor emits a complete tail record after its newline arrives', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-rollout-tail-test-'));
  const rolloutPath = path.join(dir, 'rollout.jsonl');
  const baseline = [
    JSON.stringify({ timestamp: '2026-08-03T00:00:00.000Z', type: 'event_msg', payload: { type: 'task_started', turn_id: 'turn_tail' } }),
    JSON.stringify({ timestamp: '2026-08-03T00:00:00.100Z', type: 'event_msg', payload: { type: 'agent_message', message: '延迟换行的回复' } }),
  ].join('\n');

  try {
    await fs.writeFile(rolloutPath, baseline, 'utf8');
    const cursor = await createRolloutEventCursor(rolloutPath);
    await fs.appendFile(rolloutPath, '\n', 'utf8');

    const result = await readRolloutEventsFromCursor(
      'tail_thread',
      rolloutPath,
      'tail_thread',
      cursor,
    );
    assert.equal(result.events.length, 1);
    assert.equal(result.events[0].event, 'item/completed');
    assert.equal(result.events[0].turn_id, 'turn_tail');
    assert.equal(result.events[0].payload.item.id, 'rollout_turn_tail_0001');
    assert.equal(result.events[0].payload.item.text, '延迟换行的回复');
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('connected bridge discovers only newly appended desktop rollout events', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-external-monitor-test-'));
  const rolloutPath = path.join(dir, 'rollout.jsonl');
  await fs.writeFile(rolloutPath, rolloutLines('turn_old', '旧问题', '旧回复', '00'), 'utf8');

  const backend = new ExternalThreadBackend({
    id: 'desktop_thread_1',
    sessionId: 'desktop_thread_1',
    preview: '桌面会话',
    name: '桌面会话',
    createdAt: 1,
    updatedAt: 2,
    status: { type: 'idle' },
    path: rolloutPath,
    cwd: '/tmp/desktop-workspace',
    turns: [],
  });
  const store = new StateStore(path.join(dir, 'state.json'));
  const bridge = new BridgeServer({
    backend,
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
    externalSessionPollIntervalMs: 25,
  });
  await bridge.start();
  const ws = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/ws?token=test-token`);
  const messages = [];
  ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));

  try {
    await once(ws, 'open');
    await waitFor(() => backend.listCalls >= 2);
    assert.equal(messages.some((message) => message.event === 'item/completed'), false);

    await fs.appendFile(rolloutPath, rolloutLines('turn_new', '新问题', '桌面端新回复', '10'), 'utf8');
    backend.thread.updatedAt = 3;

    await waitFor(() => messages.some((message) => (
      message.event === 'item/completed'
      && message.payload?.item?.text === '桌面端新回复'
    )));
    await waitFor(() => messages.some((message) => (
      message.event === 'turn/completed'
      && message.turn_id === 'turn_new'
    )));
    const callsAfterDiscovery = backend.listCalls;
    await waitFor(() => backend.listCalls >= callsAfterDiscovery + 3);

    assert.equal(messages.filter((message) => (
      message.event === 'item/completed'
      && message.payload?.item?.text === '桌面端新回复'
    )).length, 1);
    assert.equal(messages.filter((message) => (
      message.event === 'turn/completed'
      && message.turn_id === 'turn_new'
    )).length, 1);
    assert.equal(messages.some((message) => message.payload?.item?.text === '旧回复'), false);
  } finally {
    if (ws.readyState === WebSocket.OPEN) {
      ws.close();
      await once(ws, 'close');
    }
    await bridge.stop();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('rollout monitor reads replacement content after the rollout file shrinks', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-external-rotation-test-'));
  const rolloutPath = path.join(dir, 'rollout.jsonl');
  await fs.writeFile(
    rolloutPath,
    rolloutLines('turn_old', '旧问题', `旧回复${'x'.repeat(4000)}`, '00'),
    'utf8',
  );

  const backend = new ExternalThreadBackend({
    id: 'desktop_thread_rotation',
    sessionId: 'desktop_thread_rotation',
    preview: '轮转会话',
    name: '轮转会话',
    createdAt: 1,
    updatedAt: 2,
    status: { type: 'idle' },
    path: rolloutPath,
    cwd: '/tmp/desktop-workspace',
    turns: [],
  });
  const store = new StateStore(path.join(dir, 'state.json'));
  const bridge = new BridgeServer({
    backend,
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
    externalSessionPollIntervalMs: 25,
  });
  await bridge.start();
  const ws = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/ws?token=test-token`);
  const messages = [];
  ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));

  try {
    await once(ws, 'open');
    await waitFor(() => backend.listCalls >= 2);
    await fs.writeFile(
      rolloutPath,
      rolloutLines('turn_replaced', '新问题', '替换后的回复', '10'),
      'utf8',
    );
    backend.thread.updatedAt = 3;

    await waitFor(() => messages.some((message) => (
      message.event === 'item/completed'
      && message.payload?.item?.text === '替换后的回复'
    )));
    assert.equal(messages.some((message) => message.payload?.item?.text?.startsWith('旧回复')), false);
  } finally {
    if (ws.readyState === WebSocket.OPEN) {
      ws.close();
      await once(ws, 'close');
    }
    await bridge.stop();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('rollout monitor does not rebroadcast events already received in realtime', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-external-dedupe-test-'));
  const rolloutPath = path.join(dir, 'rollout.jsonl');
  await fs.writeFile(rolloutPath, '', 'utf8');

  const backend = new ExternalThreadBackend({
    id: 'desktop_thread_2',
    sessionId: 'desktop_thread_2',
    preview: '去重会话',
    name: '去重会话',
    createdAt: 1,
    updatedAt: 2,
    status: { type: 'active' },
    path: rolloutPath,
    cwd: '/tmp/desktop-workspace',
    turns: [],
  });
  const store = new StateStore(path.join(dir, 'state.json'));
  const bridge = new BridgeServer({
    backend,
    store,
    host: '127.0.0.1',
    port: 0,
    token: 'test-token',
    externalSessionPollIntervalMs: 25,
  });
  await bridge.start();
  const ws = new WebSocket(`ws://127.0.0.1:${bridge.address().port}/ws?token=test-token`);
  const messages = [];
  ws.on('message', (buffer) => messages.push(JSON.parse(buffer.toString('utf8'))));

  try {
    await once(ws, 'open');
    await waitFor(() => backend.listCalls >= 2);

    backend.emit('message', {
      type: 'notification',
      method: 'item/completed',
      params: {
        threadId: 'desktop_thread_2',
        turnId: 'turn_race',
        item: { type: 'agentMessage', id: 'realtime_item', text: '只通知一次' },
      },
    });
    backend.emit('message', {
      type: 'notification',
      method: 'turn/completed',
      params: {
        threadId: 'desktop_thread_2',
        turnId: 'turn_race',
        status: 'completed',
        turn: { id: 'turn_race', status: 'completed' },
      },
    });
    await waitFor(() => messages.some((message) => message.payload?.item?.text === '只通知一次'));
    await fs.appendFile(rolloutPath, rolloutLines('turn_race', '去重问题', '只通知一次', '20'), 'utf8');
    backend.thread.updatedAt = 3;

    const callsBeforeMonitor = backend.listCalls;
    await waitFor(() => backend.listCalls >= callsBeforeMonitor + 3);
    assert.equal(messages.filter((message) => message.payload?.item?.text === '只通知一次').length, 1);
    assert.equal(messages.filter((message) => (
      message.event === 'turn/completed'
      && message.turn_id === 'turn_race'
    )).length, 1);
  } finally {
    if (ws.readyState === WebSocket.OPEN) {
      ws.close();
      await once(ws, 'close');
    }
    await bridge.stop();
    await fs.rm(dir, { recursive: true, force: true });
  }
});

test('monitor poll delay doubles per consecutive failure and resets on success', () => {
  assert.equal(externalMonitorBackoffMs(5000, 0, 60_000), 5000);
  assert.equal(externalMonitorBackoffMs(5000, 1, 60_000), 10_000);
  assert.equal(externalMonitorBackoffMs(5000, 2, 60_000), 20_000);
  assert.equal(externalMonitorBackoffMs(5000, 3, 60_000), 40_000);
  assert.equal(externalMonitorBackoffMs(5000, 4, 60_000), 60_000);
  assert.equal(externalMonitorBackoffMs(5000, 9, 60_000), 60_000, 'capped');
  assert.equal(externalMonitorBackoffMs(5000, -1, 60_000), 5000, 'reset');
  // A cap below the interval must not shorten the normal poll interval.
  assert.equal(externalMonitorBackoffMs(5000, 3, 1000), 5000);
});

async function waitFor(predicate, timeoutMs = 5000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new Error('timed out');
}
