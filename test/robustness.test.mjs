import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { StateStore } from '../src/store.mjs';
import { sweepSessionUploads } from '../src/bridge/file-reader.mjs';

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
