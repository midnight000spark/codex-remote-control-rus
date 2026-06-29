import test from 'node:test';
import assert from 'node:assert/strict';

import {
  compareCodexVersions,
  resolveCodexBin,
} from '../src/codex-bin-resolver.mjs';

test('codex bin resolver selects the highest available version', async () => {
  const chosen = await resolveCodexBin({
    candidates: ['/opt/homebrew/bin/codex', '/Applications/Codex.app/Contents/Resources/codex'],
    runVersion: async (candidate) => {
      if (candidate === '/opt/homebrew/bin/codex') return 'codex-cli 0.130.0';
      if (candidate === '/Applications/Codex.app/Contents/Resources/codex') return 'codex-cli 0.142.3';
      throw new Error(`unexpected candidate: ${candidate}`);
    },
  });

  assert.equal(chosen, '/Applications/Codex.app/Contents/Resources/codex');
});

test('codex bin resolver keeps explicit bin selections', async () => {
  const chosen = await resolveCodexBin({
    explicitBin: '/custom/codex',
    candidates: ['/opt/homebrew/bin/codex', '/Applications/Codex.app/Contents/Resources/codex'],
    runVersion: async () => {
      throw new Error('explicit selections should not probe versions');
    },
  });

  assert.equal(chosen, '/custom/codex');
});

test('codex version comparison handles prerelease versions', () => {
  assert.equal(compareCodexVersions('0.142.3', '0.130.0'), 1);
  assert.equal(compareCodexVersions('0.142.3', '0.142.3-alpha.2'), 1);
  assert.equal(compareCodexVersions('0.142.3-alpha.2', '0.142.3-alpha.1'), 1);
});
