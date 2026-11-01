import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { randomUUID } from 'node:crypto';
import { StateStore } from './store.mjs';
import { MockBackend } from './backends/mock.mjs';
import { CodexBackend } from './backends/codex.mjs';
import { BridgeServer } from './bridge-server.mjs';
import { resolveCodexBin } from './codex-bin-resolver.mjs';

function defaultDataDir() {
  return path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), '.config'), 'codex_remote_control');
}

function legacyDataDir() {
  return path.join(process.cwd(), 'data');
}

function parseArgs(argv) {
  const dataDir = defaultDataDir();
  const out = {
    backend: 'mock',
    listen: '127.0.0.1:8787',
    state: path.join(dataDir, 'bridge-state.json'),
    tokenFile: path.join(dataDir, 'bridge-token.txt'),
    bridgeIdFile: path.join(dataDir, 'bridge-id.txt'),
    clearStateOnStart: false,
    syncLog: null,
    protocolLog: null,
    codexBin: null,
    codexArgs: [],
    codexConfigs: [],
  };

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--backend') out.backend = argv[++i];
    else if (arg === '--listen') out.listen = argv[++i];
    else if (arg === '--state') out.state = argv[++i];
    else if (arg === '--token-file') out.tokenFile = argv[++i];
    else if (arg === '--bridge-id-file') out.bridgeIdFile = argv[++i];
    else if (arg === '--clear-state-on-start') out.clearStateOnStart = true;
    else if (arg === '--sync-log') out.syncLog = argv[++i];
    else if (arg === '--protocol-log') out.protocolLog = argv[++i];
    else if (arg === '--codex-bin') out.codexBin = argv[++i];
    else if (arg === '--codex-arg') out.codexArgs.push(argv[++i]);
    else if (arg === '--codex-config') out.codexConfigs.push(argv[++i]);
    else if (arg === '--help' || arg === '-h') {
      out.help = true;
    }
  }
  return out;
}

function parseListen(listen) {
  const [host, portRaw] = listen.includes(':') ? listen.split(/:(?=[^:]*$)/) : [listen, '8787'];
  return { host, port: Number(portRaw) || 8787 };
}

async function migrateLegacyRuntimeFile(sourceFile, targetFile) {
  if (sourceFile === targetFile) return;
  try {
    await fs.access(targetFile);
    return;
  } catch {}
  try {
    await fs.access(sourceFile);
  } catch {
    return;
  }
  await fs.mkdir(path.dirname(targetFile), { recursive: true });
  try {
    await fs.rename(sourceFile, targetFile);
  } catch (error) {
    if (error?.code !== 'EXDEV') throw error;
    await fs.copyFile(sourceFile, targetFile);
    await fs.unlink(sourceFile);
  }
  console.log(`Migrated legacy runtime file: ${sourceFile} -> ${targetFile}`);
}

async function maybeMigrateLegacyRuntimeFiles(args) {
  const dataDir = defaultDataDir();
  const legacyDir = legacyDataDir();
  const mappings = [
    ['bridge-token.txt', args.tokenFile],
    ['bridge-id.txt', args.bridgeIdFile],
    ['bridge-state.json', args.state],
    ['bridge-sync.log', args.syncLog],
  ];
  for (const [filename, targetFile] of mappings) {
    if (!targetFile) continue;
    if (path.dirname(targetFile) !== dataDir) continue;
    await migrateLegacyRuntimeFile(path.join(legacyDir, filename), targetFile);
  }
}

async function loadOrCreateToken(tokenFile) {
  await fs.mkdir(path.dirname(tokenFile), { recursive: true });
  try {
    const token = (await fs.readFile(tokenFile, 'utf8')).trim();
    if (token) return token;
  } catch {}
  const token = cryptoRandomToken();
  await fs.writeFile(tokenFile, `${token}\n`, 'utf8');
  return token;
}

async function loadOrCreateBridgeId(bridgeIdFile) {
  await fs.mkdir(path.dirname(bridgeIdFile), { recursive: true });
  try {
    const bridgeId = (await fs.readFile(bridgeIdFile, 'utf8')).trim();
    if (bridgeId) return bridgeId;
  } catch {}
  const bridgeId = randomUUID();
  await fs.writeFile(bridgeIdFile, `${bridgeId}\n`, 'utf8');
  return bridgeId;
}

function cryptoRandomToken() {
  return [...crypto.getRandomValues(new Uint8Array(24))]
    .map((n) => n.toString(16).padStart(2, '0'))
    .join('');
}

const crypto = globalThis.crypto ?? (await import('node:crypto')).webcrypto;

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(`Usage: node src/index.mjs [options]

Options:
  --backend mock|codex
  --listen HOST:PORT
  --state PATH
  --token-file PATH
  --bridge-id-file PATH
  --clear-state-on-start  clear local bridge history before startup
  --sync-log PATH        enable sync debug logging to a file
  --protocol-log PATH    capture raw bridge<->codex protocol lines
  --codex-bin PATH
  --codex-arg VALUE      repeatable
  --codex-config KEY=VALUE repeatable
`);
    process.exit(0);
  }

  await maybeMigrateLegacyRuntimeFiles(args);
  const { host, port } = parseListen(args.listen);
  const token = await loadOrCreateToken(args.tokenFile);
  const bridgeId = await loadOrCreateBridgeId(args.bridgeIdFile);
  const store = new StateStore(args.state);
  if (args.clearStateOnStart) {
    await store.clear();
  }
  const codexBin = args.backend === 'codex'
    ? await resolveCodexBin({ explicitBin: args.codexBin })
    : null;
  const backend = args.backend === 'codex'
    ? new CodexBackend({
        codexBin,
        codexArgs: args.codexArgs,
        codexConfigs: args.codexConfigs,
        protocolLogPath: args.protocolLog,
      })
    : new MockBackend();
  const bridge = new BridgeServer({ backend, store, host, port, token, bridgeId, syncLogPath: args.syncLog });

  await bridge.start();
  let shuttingDown = false;
  const shutdown = async () => {
    if (shuttingDown) return;
    shuttingDown = true;
    try {
      await bridge.stop();
    } catch {}
    try {
      await store.flush();
    } catch {}
    process.exit(0);
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
  const address = bridge.address();
  console.log(`Codex bridge ready on ${address.host}:${address.port}`);
  console.log(`Bridge ID file: ${args.bridgeIdFile}`);
  console.log(`Token file: ${args.tokenFile}`);
  console.log(`State file: ${args.state}`);
  if (args.syncLog) {
    console.log(`Sync log: ${args.syncLog}`);
  }
  if (args.protocolLog) {
    console.log(`Protocol log: ${args.protocolLog}`);
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
