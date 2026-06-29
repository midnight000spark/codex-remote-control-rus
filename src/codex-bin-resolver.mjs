import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const DEFAULT_CODEX_BIN_CANDIDATES = [
  'codex',
  '/Applications/Codex.app/Contents/Resources/codex',
];

export async function resolveCodexBin({
  explicitBin = null,
  candidates = DEFAULT_CODEX_BIN_CANDIDATES,
  runVersion = readCodexVersionOutput,
} = {}) {
  if (typeof explicitBin === 'string' && explicitBin.trim()) {
    return explicitBin.trim();
  }

  const probed = [];
  for (const candidate of uniqueCandidates(candidates)) {
    try {
      const output = await runVersion(candidate);
      const version = extractCodexVersion(output);
      if (version) {
        probed.push({ candidate, version });
      }
    } catch {}
  }

  probed.sort((left, right) => compareCodexVersions(right.version, left.version));
  return probed[0]?.candidate ?? 'codex';
}

export function compareCodexVersions(left, right) {
  const leftVersion = parseCodexVersion(left);
  const rightVersion = parseCodexVersion(right);
  for (let index = 0; index < 3; index += 1) {
    const diff = leftVersion.core[index] - rightVersion.core[index];
    if (diff !== 0) return diff > 0 ? 1 : -1;
  }
  return comparePrerelease(leftVersion.prerelease, rightVersion.prerelease);
}

function uniqueCandidates(candidates) {
  return Array.from(new Set(candidates.filter((candidate) => typeof candidate === 'string' && candidate.trim()).map((candidate) => candidate.trim())));
}

async function readCodexVersionOutput(candidate) {
  const { stdout, stderr } = await execFileAsync(candidate, ['--version'], { timeout: 5000 });
  return `${stdout ?? ''}\n${stderr ?? ''}`;
}

function extractCodexVersion(output) {
  return String(output ?? '').match(/\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?/)?.[0] ?? null;
}

function parseCodexVersion(value) {
  const version = extractCodexVersion(value) ?? '0.0.0';
  const [coreRaw, prerelease = ''] = version.split('-', 2);
  const core = coreRaw.split('.').map((part) => Number(part) || 0);
  while (core.length < 3) core.push(0);
  return { core: core.slice(0, 3), prerelease };
}

function comparePrerelease(left, right) {
  if (!left && !right) return 0;
  if (!left) return 1;
  if (!right) return -1;

  const leftParts = left.split('.');
  const rightParts = right.split('.');
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const leftPart = leftParts[index];
    const rightPart = rightParts[index];
    if (leftPart == null) return -1;
    if (rightPart == null) return 1;
    const compared = comparePrereleasePart(leftPart, rightPart);
    if (compared !== 0) return compared;
  }
  return 0;
}

function comparePrereleasePart(left, right) {
  const leftNumber = /^\d+$/.test(left) ? Number(left) : null;
  const rightNumber = /^\d+$/.test(right) ? Number(right) : null;
  if (leftNumber != null && rightNumber != null) {
    if (leftNumber === rightNumber) return 0;
    return leftNumber > rightNumber ? 1 : -1;
  }
  if (leftNumber != null) return -1;
  if (rightNumber != null) return 1;
  return left.localeCompare(right);
}
