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
  const resolvedPath = resolveSessionFilePath(store, sessionId, requestedPath);
  const raw = await fs.readFile(resolvedPath);
  const truncated = raw.byteLength > maxBytes;
  const bytes = truncated ? raw.subarray(0, maxBytes) : raw;
  return {
    resolvedPath,
    content: bytes.toString('utf8'),
    truncated,
    bytes: bytes.byteLength,
  };
}

export async function readSessionImage(store, sessionId, requestedPath) {
  const session = sessionId ? store.getSession(sessionId) : null;
  if (!session) {
    const error = new Error(`unknown session: ${sessionId}`);
    error.code = 'SESSION_NOT_FOUND';
    throw error;
  }
  const resolvedPath = resolveSessionFilePath(store, sessionId, requestedPath);
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

function resolveSessionFilePath(store, sessionId, requestedPath) {
  const session = sessionId ? store.getSession(sessionId) : null;
  const cwd = session?.cwd || session?.thread?.cwd || null;
  const resolvedPath = path.isAbsolute(requestedPath)
    ? requestedPath
    : cwd
      ? path.resolve(cwd, requestedPath)
      : null;
  if (!resolvedPath) {
    throw new Error(`cannot resolve relative path without session cwd: ${requestedPath}`);
  }
  return resolvedPath;
}

export function clampMaxBytes(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return 200_000;
  return Math.max(8_192, Math.min(500_000, Math.trunc(parsed)));
}
