import fs from 'node:fs/promises';
import path from 'node:path';

const MAX_DIRECTORY_ENTRIES = 500;
const DIRECTORY_SCAN_BATCH_SIZE = 32;

function compareDirectoryNames(left, right) {
  return left.name.localeCompare(right.name, undefined, { numeric: true, sensitivity: 'base' });
}

async function resolveDirectoryEntry(resolvedPath, entry) {
  const entryPath = path.join(resolvedPath, entry.name);
  if (entry.isDirectory()) {
    return { name: entry.name, path: entryPath };
  }
  if (!entry.isSymbolicLink()) return null;
  try {
    return (await fs.stat(entryPath)).isDirectory()
      ? { name: entry.name, path: entryPath }
      : null;
  } catch {
    return null;
  }
}

export async function listDirectories(requestedPath) {
  const inputPath = typeof requestedPath === 'string' ? requestedPath.trim() : '';
  const resolvedPath = path.resolve(inputPath || process.cwd());
  const stat = await fs.stat(resolvedPath);
  if (!stat.isDirectory()) {
    const error = new Error(`not a directory: ${inputPath || resolvedPath}`);
    error.code = 'NOT_DIRECTORY';
    throw error;
  }

  const entries = await fs.readdir(resolvedPath, { withFileTypes: true });
  entries.sort(compareDirectoryNames);
  const directories = [];
  for (
    let index = 0;
    index < entries.length && directories.length <= MAX_DIRECTORY_ENTRIES;
    index += DIRECTORY_SCAN_BATCH_SIZE
  ) {
    const batch = entries.slice(index, index + DIRECTORY_SCAN_BATCH_SIZE);
    const resolvedBatch = await Promise.all(
      batch.map((entry) => resolveDirectoryEntry(resolvedPath, entry)),
    );
    for (const directory of resolvedBatch) {
      if (!directory) continue;
      directories.push(directory);
      if (directories.length > MAX_DIRECTORY_ENTRIES) break;
    }
  }

  const rootPath = path.parse(resolvedPath).root;
  return {
    resolvedPath,
    parentPath: resolvedPath === rootPath ? null : path.dirname(resolvedPath),
    directories: directories.slice(0, MAX_DIRECTORY_ENTRIES),
    truncated: directories.length > MAX_DIRECTORY_ENTRIES,
  };
}
