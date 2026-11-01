import test from 'node:test';
import assert from 'node:assert/strict';
import { defaultSandboxPolicy } from '../src/bridge/session-normalization.mjs';

test('default sandbox policy stays full access when the env var is unset', () => {
  assert.deepEqual(defaultSandboxPolicy({}), { type: 'dangerFullAccess' });
  assert.deepEqual(defaultSandboxPolicy({ CODEX_REMOTE_DEFAULT_SANDBOX: '  ' }), { type: 'dangerFullAccess' });
});

test('CODEX_REMOTE_DEFAULT_SANDBOX overrides the default sandbox policy', () => {
  assert.deepEqual(
    defaultSandboxPolicy({ CODEX_REMOTE_DEFAULT_SANDBOX: 'workspace-write' }),
    {
      type: 'workspaceWrite',
      writableRoots: [],
      networkAccess: false,
      excludeTmpdirEnvVar: false,
      excludeSlashTmp: false,
    },
  );
  assert.deepEqual(
    defaultSandboxPolicy({ CODEX_REMOTE_DEFAULT_SANDBOX: 'read-only' }),
    { type: 'readOnly', networkAccess: false },
  );
});

test('an unparsable sandbox override fails loudly instead of granting full access', () => {
  assert.throws(
    () => defaultSandboxPolicy({ CODEX_REMOTE_DEFAULT_SANDBOX: 'workspace-writ' }),
    /invalid CODEX_REMOTE_DEFAULT_SANDBOX/,
  );
});
