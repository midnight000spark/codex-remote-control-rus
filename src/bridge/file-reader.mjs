import fs from 'node:fs/promises';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

const IMAGE_MIME_TYPES = new Map([
  ['.avif', 'image/avif'],
  ['.bmp', 'image/bmp'],
  ['.gif', 'image/gif'],
  ['.ico', 'image/x-icon'],
  ['.jpeg', 'image/jpeg'],
  ['.jpg', 'image/jpeg'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml'],
  ['.webp', 'image/webp'],
]);

const FILE_MIME_TYPES = new Map([
  ...IMAGE_MIME_TYPES,
  ['.a', 'application/octet-stream'],
  ['.aac', 'audio/aac'],
  ['.apk', 'application/vnd.android.package-archive'],
  ['.avi', 'video/x-msvideo'],
  ['.bin', 'application/octet-stream'],
  ['.c', 'text/x-c'],
  ['.conf', 'text/plain'],
  ['.cpp', 'text/x-c++src'],
  ['.css', 'text/css'],
  ['.csv', 'text/csv'],
  ['.db', 'application/vnd.sqlite3'],
  ['.doc', 'application/msword'],
  ['.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
  ['.dll', 'application/vnd.microsoft.portable-executable'],
  ['.dylib', 'application/octet-stream'],
  ['.exe', 'application/vnd.microsoft.portable-executable'],
  ['.flac', 'audio/flac'],
  ['.go', 'text/x-go'],
  ['.gradle', 'text/plain'],
  ['.gz', 'application/gzip'],
  ['.h', 'text/x-c'],
  ['.heic', 'image/heic'],
  ['.heif', 'image/heif'],
  ['.html', 'text/html'],
  ['.ini', 'text/plain'],
  ['.java', 'text/x-java-source'],
  ['.jar', 'application/java-archive'],
  ['.class', 'application/java-vm'],
  ['.js', 'text/javascript'],
  ['.json', 'application/json'],
  ['.jsx', 'text/jsx'],
  ['.kt', 'text/x-kotlin'],
  ['.kts', 'text/x-kotlin'],
  ['.log', 'text/plain'],
  ['.m4a', 'audio/mp4'],
  ['.m4v', 'video/mp4'],
  ['.md', 'text/markdown'],
  ['.mjs', 'text/javascript'],
  ['.mkv', 'video/x-matroska'],
  ['.mov', 'video/quicktime'],
  ['.mp3', 'audio/mpeg'],
  ['.mp4', 'video/mp4'],
  ['.ogg', 'audio/ogg'],
  ['.o', 'application/octet-stream'],
  ['.otf', 'font/otf'],
  ['.opus', 'audio/ogg'],
  ['.pdf', 'application/pdf'],
  ['.ppt', 'application/vnd.ms-powerpoint'],
  ['.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation'],
  ['.properties', 'text/plain'],
  ['.py', 'text/x-python'],
  ['.rar', 'application/vnd.rar'],
  ['.rs', 'text/x-rust'],
  ['.sh', 'text/x-shellscript'],
  ['.sql', 'application/sql'],
  ['.sqlite', 'application/vnd.sqlite3'],
  ['.sqlite3', 'application/vnd.sqlite3'],
  ['.so', 'application/octet-stream'],
  ['.tar', 'application/x-tar'],
  ['.toml', 'application/toml'],
  ['.ts', 'text/typescript'],
  ['.tsx', 'text/tsx'],
  ['.txt', 'text/plain'],
  ['.ttf', 'font/ttf'],
  ['.wav', 'audio/wav'],
  ['.webm', 'video/webm'],
  ['.wasm', 'application/wasm'],
  ['.woff', 'font/woff'],
  ['.woff2', 'font/woff2'],
  ['.xls', 'application/vnd.ms-excel'],
  ['.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
  ['.xml', 'application/xml'],
  ['.yaml', 'application/yaml'],
  ['.yml', 'application/yaml'],
  ['.zip', 'application/zip'],
  ['.7z', 'application/x-7z-compressed'],
]);

const IMAGE_EXTENSIONS = new Map([
  ['image/avif', '.avif'],
  ['image/bmp', '.bmp'],
  ['image/gif', '.gif'],
  ['image/x-icon', '.ico'],
  ['image/vnd.microsoft.icon', '.ico'],
  ['image/jpeg', '.jpg'],
  ['image/png', '.png'],
  ['image/svg+xml', '.svg'],
  ['image/webp', '.webp'],
]);

export const MAX_IMAGE_BYTES = 12_000_000;

export async function readSessionFile(store, sessionId, requestedPath, maxBytes) {
  const file = await inspectSessionFile(store, sessionId, requestedPath, maxBytes);
  const isText = file.contentKind === 'text';
  const textPreview = isText ? decodeUtf8Prefix(file.sample) : null;
  return {
    resolvedPath: file.resolvedPath,
    name: file.name,
    mimeType: file.mimeType,
    contentKind: file.contentKind,
    encoding: isText ? 'utf-8' : null,
    content: textPreview?.content ?? null,
    truncated: isText && file.size > (textPreview?.bytes ?? 0),
    bytes: textPreview?.bytes ?? 0,
    size: file.size,
    downloadable: true,
  };
}

export async function describeSessionFile(store, sessionId, requestedPath) {
  const file = await inspectSessionFile(store, sessionId, requestedPath, 8_192);
  return {
    resolvedPath: file.resolvedPath,
    name: file.name,
    mimeType: file.mimeType,
    contentKind: file.contentKind,
    size: file.size,
  };
}

export async function readSessionImage(store, sessionId, requestedPath) {
  const session = sessionId ? store.getSession(sessionId) : null;
  if (!session) {
    const error = new Error(`unknown session: ${sessionId}`);
    error.code = 'SESSION_NOT_FOUND';
    throw error;
  }
  const resolvedPath = await resolveSessionFilePath(store, sessionId, requestedPath);
  const mimeType = IMAGE_MIME_TYPES.get(path.extname(resolvedPath).toLowerCase());
  if (!mimeType) {
    const error = new Error(`unsupported image type: ${requestedPath}`);
    error.code = 'UNSUPPORTED_IMAGE';
    throw error;
  }
  const stat = await fs.stat(resolvedPath);
  if (!stat.isFile()) {
    const error = new Error(`not a file: ${requestedPath}`);
    error.code = 'NOT_FILE';
    throw error;
  }
  if (stat.size > MAX_IMAGE_BYTES) {
    const error = new Error(`image exceeds ${MAX_IMAGE_BYTES} bytes`);
    error.code = 'IMAGE_TOO_LARGE';
    throw error;
  }
  const content = await fs.readFile(resolvedPath);
  if (content.byteLength > MAX_IMAGE_BYTES) {
    const error = new Error(`image exceeds ${MAX_IMAGE_BYTES} bytes`);
    error.code = 'IMAGE_TOO_LARGE';
    throw error;
  }
  return {
    resolvedPath,
    content,
    mimeType,
    bytes: content.byteLength,
  };
}

export async function writeSessionImageUpload(store, sessionId, contentType, content) {
  const session = sessionId ? store.getSession(sessionId) : null;
  if (!session) {
    const error = new Error(`unknown session: ${sessionId}`);
    error.code = 'SESSION_NOT_FOUND';
    throw error;
  }
  const mimeType = String(contentType ?? '').split(';', 1)[0].trim().toLowerCase();
  const extension = IMAGE_EXTENSIONS.get(mimeType);
  if (!extension) {
    const error = new Error(`unsupported image type: ${mimeType}`);
    error.code = 'UNSUPPORTED_IMAGE';
    throw error;
  }
  const bytes = Buffer.isBuffer(content) ? content : Buffer.from(content ?? []);
  if (bytes.byteLength === 0) {
    const error = new Error('image upload is empty');
    error.code = 'EMPTY_IMAGE';
    throw error;
  }
  if (bytes.byteLength > MAX_IMAGE_BYTES) {
    const error = new Error(`image exceeds ${MAX_IMAGE_BYTES} bytes`);
    error.code = 'IMAGE_TOO_LARGE';
    throw error;
  }
  const uploadDir = path.join(path.dirname(store.filePath), 'uploads');
  await fs.mkdir(uploadDir, { recursive: true });
  const resolvedPath = path.join(uploadDir, `${randomUUID()}${extension}`);
  await fs.writeFile(resolvedPath, bytes);
  return {
    resolvedPath,
    mimeType,
    bytes: bytes.byteLength,
  };
}

async function realpathOrResolve(value) {
  try {
    return await fs.realpath(value);
  } catch {
    return path.resolve(value);
  }
}

export const DEFAULT_UPLOAD_TTL_MS = 7 * 24 * 60 * 60 * 1000;

// Uploaded images are copied into the bridge state directory and referenced by
// path from the conversation, so nothing ever deletes them. Sweep the ones that
// no stored session mentions any more and that are older than the TTL;
// referenced files are always kept.
//
// References are matched by basename: the same upload is spelled as an absolute
// path by the bridge, but a relative path or a differently rooted state
// directory must not make a live image look unreferenced.
function collectReferenceNames(value, names) {
  if (typeof value === 'string') {
    if (value) names.add(path.basename(value));
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) collectReferenceNames(item, names);
    return;
  }
  if (value && typeof value === 'object') {
    for (const item of Object.values(value)) collectReferenceNames(item, names);
  }
}

export async function sweepSessionUploads(store, { maxAgeMs = DEFAULT_UPLOAD_TTL_MS, now = Date.now() } = {}) {
  const uploadDir = path.join(path.dirname(store.filePath), 'uploads');
  let entries;
  try {
    entries = await fs.readdir(uploadDir, { withFileTypes: true });
  } catch {
    return { removed: 0, kept: 0 };
  }

  const referencedNames = new Set();
  try {
    store.forEachSession((session) => collectReferenceNames(session, referencedNames));
  } catch {
    // If the sessions cannot be read, keep everything rather than risk deleting
    // an image that is still on screen.
    return { removed: 0, kept: entries.length };
  }

  let removed = 0;
  let kept = 0;
  for (const entry of entries) {
    if (referencedNames.has(entry.name)) {
      kept += 1;
      continue;
    }
    const filePath = path.join(uploadDir, entry.name);
    try {
      // lstat, not stat: a symlink is removed as a link. Following it would
      // either delete a file outside the uploads directory or leave the link
      // behind forever.
      const stat = await fs.lstat(filePath);
      // `<=` keeps a file that sits exactly on the TTL boundary.
      if (now - stat.mtimeMs <= maxAgeMs) {
        kept += 1;
        continue;
      }
      await fs.unlink(filePath);
      removed += 1;
    } catch {
      kept += 1;
    }
  }
  return { removed, kept };
}

// Reads are confined to the session workspace plus the bridge-managed upload
// directory (where writeSessionImageUpload stores client uploads).
function allowedReadRoots(store, cwd) {
  return [cwd, path.join(path.dirname(store.filePath), 'uploads')].filter(Boolean);
}

function isInsideRoot(rootReal, targetReal) {
  return targetReal === rootReal || targetReal.startsWith(rootReal + path.sep);
}

async function resolveSessionFilePath(store, sessionId, requestedPath) {
  const session = sessionId ? store.getSession(sessionId) : null;
  if (!session) {
    const error = new Error(`unknown session: ${sessionId}`);
    error.code = 'SESSION_NOT_FOUND';
    throw error;
  }
  const cwd = session?.cwd || session?.thread?.cwd || null;
  const resolvedPath = path.isAbsolute(requestedPath)
    ? requestedPath
    : cwd
      ? path.resolve(cwd, requestedPath)
      : null;
  if (!resolvedPath) {
    throw new Error(`cannot resolve relative path without session cwd: ${requestedPath}`);
  }
  if (cwd) {
    // realpath resolves symlinks, so a link inside the workspace that points
    // outside it is rejected as well. Uploads live outside the workspace but
    // are written by the bridge itself, so they stay readable.
    const targetReal = await realpathOrResolve(resolvedPath);
    const roots = await Promise.all(allowedReadRoots(store, cwd).map(realpathOrResolve));
    if (!roots.some((rootReal) => isInsideRoot(rootReal, targetReal))) {
      const error = new Error('path is outside the session workspace');
      error.code = 'PATH_OUTSIDE_SESSION';
      throw error;
    }
  }
  return resolvedPath;
}

async function inspectSessionFile(store, sessionId, requestedPath, sampleBytes) {
  const resolvedPath = await resolveSessionFilePath(store, sessionId, requestedPath);
  const stat = await fs.stat(resolvedPath);
  if (!stat.isFile()) {
    const error = new Error(`not a file: ${requestedPath}`);
    error.code = 'NOT_FILE';
    throw error;
  }
  const sample = await readFilePrefix(resolvedPath, Math.min(stat.size, sampleBytes));
  const mimeType = detectFileMimeType(resolvedPath, sample);
  return {
    resolvedPath,
    name: path.basename(resolvedPath),
    mimeType,
    contentKind: detectContentKind(mimeType, sample),
    sample,
    size: stat.size,
  };
}

async function readFilePrefix(filePath, maxBytes) {
  if (maxBytes <= 0) return Buffer.alloc(0);
  const handle = await fs.open(filePath, 'r');
  try {
    const buffer = Buffer.alloc(maxBytes);
    const { bytesRead } = await handle.read(buffer, 0, maxBytes, 0);
    return buffer.subarray(0, bytesRead);
  } finally {
    await handle.close();
  }
}

function detectFileMimeType(filePath, sample) {
  const extensionMimeType = FILE_MIME_TYPES.get(path.extname(filePath).toLowerCase());
  if (extensionMimeType) return extensionMimeType;
  if (sample.length >= 8 && sample.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return 'image/png';
  }
  if (sample.length >= 3 && sample[0] === 0xff && sample[1] === 0xd8 && sample[2] === 0xff) {
    return 'image/jpeg';
  }
  if (sample.length >= 6 && (sample.subarray(0, 6).toString('ascii') === 'GIF87a' || sample.subarray(0, 6).toString('ascii') === 'GIF89a')) {
    return 'image/gif';
  }
  if (sample.length >= 12 && sample.subarray(0, 4).toString('ascii') === 'RIFF' && sample.subarray(8, 12).toString('ascii') === 'WEBP') {
    return 'image/webp';
  }
  if (sample.length >= 5 && sample.subarray(0, 5).toString('ascii') === '%PDF-') {
    return 'application/pdf';
  }
  if (sample.length >= 4 && sample[0] === 0x50 && sample[1] === 0x4b && sample[2] === 0x03 && sample[3] === 0x04) {
    return 'application/zip';
  }
  return looksLikeUtf8Text(sample) ? 'text/plain' : 'application/octet-stream';
}

function detectContentKind(mimeType, sample) {
  if (mimeType.startsWith('image/')) return 'image';
  if (mimeType === 'application/pdf') return 'pdf';
  if (mimeType.startsWith('audio/')) return 'audio';
  if (mimeType.startsWith('video/')) return 'video';
  return looksLikeUtf8Text(sample) ? 'text' : 'binary';
}

function looksLikeUtf8Text(sample) {
  if (sample.length === 0) return true;
  if (sample.includes(0)) return false;
  let controlBytes = 0;
  for (const byte of sample) {
    if (byte < 0x20 && byte !== 0x09 && byte !== 0x0a && byte !== 0x0d) {
      controlBytes += 1;
    }
  }
  if (controlBytes > Math.max(1, Math.floor(sample.length / 20))) return false;
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(sample, { stream: true });
    return true;
  } catch {
    return false;
  }
}

function decodeUtf8Prefix(sample) {
  for (let trimmedBytes = 0; trimmedBytes <= Math.min(3, sample.length); trimmedBytes += 1) {
    const bytes = sample.length - trimmedBytes;
    try {
      return {
        content: new TextDecoder('utf-8', { fatal: true }).decode(sample.subarray(0, bytes)),
        bytes,
      };
    } catch {}
  }
  return { content: sample.toString('utf8'), bytes: sample.byteLength };
}

export function clampMaxBytes(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 200_000;
  return Math.max(8_192, Math.min(500_000, Math.trunc(parsed)));
}
