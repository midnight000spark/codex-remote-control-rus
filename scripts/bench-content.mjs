#!/usr/bin/env node
// Synthetic latency benchmark for the bridge content pipeline.
// Measures how expensive session.content / session.sync are for a large session.
//
// Usage: node scripts/bench-content.mjs
// Env: BENCH_TURNS (default 100), BENCH_EVENTS (default 500), BENCH_ROUNDS (default 10)

import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { StateStore } from '../src/store.mjs';
import { SessionContentController } from '../src/bridge/session-content-controller.mjs';

const TURNS = Number(process.env.BENCH_TURNS ?? 100);
const EVENTS = Number(process.env.BENCH_EVENTS ?? 500);
const ROUNDS = Number(process.env.BENCH_ROUNDS ?? 10);
const SESSION_ID = 'bench_session';

const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'codex-bench-'));
const store = new StateStore(path.join(dir, 'state.json'));
await store.load();

await store.upsertSession({
  session_id: SESSION_ID,
  thread_id: SESSION_ID,
  title: 'bench',
  cwd: '/tmp/bench',
  model: 'mock-gpt-5',
  backend: 'mock',
  preview: '',
  active: true,
  thread: {
    id: SESSION_ID,
    sessionId: SESSION_ID,
    cwd: '/tmp/bench',
    status: { type: 'idle' },
    turns: [],
  },
});

for (let index = 0; index < EVENTS; index += 1) {
  const turnId = `turn_${index % TURNS}`;
  if (index % 5 === 0) {
    await store.appendEvent(SESSION_ID, {
      event: 'turn/input',
      turn_id: turnId,
      text: `prompt ${index} ${'p'.repeat(40)}`,
    });
  } else {
    await store.appendEvent(SESSION_ID, {
      event: 'item/agentMessage/delta',
      turn_id: turnId,
      delta: `chunk ${index} ${'x'.repeat(120)}`,
    });
  }
}

const turns = Array.from({ length: TURNS }, (_, index) => ({
  id: `turn_${index}`,
  itemsView: 'full',
  status: 'completed',
  items: [
    {
      type: 'userMessage',
      id: `user_${index}`,
      content: [{ type: 'text', text: `prompt ${index}` }],
    },
    {
      type: 'agentMessage',
      id: `agent_${index}`,
      text: `answer ${index} ${'y'.repeat(400)}`,
    },
  ],
}));

const backend = {
  name: 'bench',
  async readThread(threadId) {
    return {
      thread: {
        id: threadId,
        sessionId: threadId,
        cwd: '/tmp/bench',
        status: { type: 'idle' },
        turns,
      },
    };
  },
  async listTurnItems() {
    return { data: [] };
  },
};

const controller = new SessionContentController({ backend, store });

async function measure(label, run) {
  const times = [];
  for (let round = 0; round < ROUNDS; round += 1) {
    const started = performance.now();
    await run();
    times.push(performance.now() - started);
  }
  times.sort((left, right) => left - right);
  const median = times[Math.floor(times.length / 2)];
  const mean = times.reduce((sum, value) => sum + value, 0) / times.length;
  console.log(
    `${label.padEnd(24)} median ${median.toFixed(1).padStart(8)}ms  mean ${mean.toFixed(1).padStart(8)}ms  min ${times[0].toFixed(1)}ms  max ${times[times.length - 1].toFixed(1)}ms`,
  );
  return { label, median, mean };
}

const lastSeq = store.getLastSeq(SESSION_ID);
console.log(`synthetic session: ${TURNS} turns, ${EVENTS} stored events, ${ROUNDS} rounds\n`);

const results = [];
results.push(await measure('session.content', () => controller.buildSessionContent(SESSION_ID)));
results.push(await measure('session.sync(since=0)', () => controller.buildSessionSync(SESSION_ID, 0)));
results.push(await measure('session.sync(tail)', () => controller.buildSessionSync(SESSION_ID, Math.max(lastSeq - 10, 1))));

await store.flush();
await fs.rm(dir, { recursive: true, force: true });
console.log(`\nJSON: ${JSON.stringify(results)}`);