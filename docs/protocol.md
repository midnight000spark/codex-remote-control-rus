# Bridge Protocol

The bridge exposes a small HTTP surface and a token-protected WebSocket protocol.

## HTTP

`GET /healthz`

```json
{ "ok": true, "name": "codex-remote-control", "version": "0.1.0" }
```

`GET /v1/sessions`

```json
{ "sessions": [] }
```

`GET /v1/image?session_id=SESSION_ID&path=PATH&token=TOKEN` returns a session image.

`POST /v1/image?session_id=SESSION_ID&token=TOKEN` uploads an image using its `image/*` content type and returns a bridge-local path suitable for a Codex `localImage` input:

```json
{ "ok": true, "path": "/absolute/path/to/upload.png", "mimeType": "image/png", "bytes": 1234 }
```

## WebSocket Authentication

Clients connect with the bridge token either in the query string or `x-bridge-token` header:

```text
ws://HOST:PORT/?token=TOKEN
```

The first server message is a hello payload:

```json
{
  "type": "hello",
  "ok": true,
  "payload": {
    "name": "codex-remote-control",
    "version": "0.1.0",
    "server_time": "2026-05-24T00:00:00.000Z"
  }
}
```

## Message Envelope

Client requests use:

```json
{
  "id": "request-id",
  "type": "session.list",
  "payload": {}
}
```

Successful responses use:

```json
{
  "type": "response",
  "id": "request-id",
  "ok": true,
  "payload": {}
}
```

Errors use:

```json
{
  "type": "error",
  "id": "request-id",
  "ok": false,
  "error": {
    "code": "unknown_message",
    "message": "unknown message type: example"
  }
}
```

Backend notifications are broadcast as:

```json
{
  "type": "event",
  "event": "turn/started",
  "session_id": "thread-id",
  "thread_id": "thread-id",
  "turn_id": "turn-id",
  "payload": {}
}
```

## Client Message Types

| Type | Purpose |
| --- | --- |
| `ping` | Liveness check |
| `model.list` | List Codex models |
| `session.list` | List known sessions and backend threads |
| `session.start` | Start a Codex thread |
| `session.resume` | Resume an existing thread |
| `session.update` | Update local session approval, permission, or sandbox settings |
| `session.content` | Fetch an authoritative full session snapshot |
| `session.sync` | Fetch changed turn snapshots since a sequence cursor |
| `turn.send` | Start a new turn |
| `turn.steer` | Send additional text to an active turn |
| `turn.interrupt` | Interrupt a turn |
| `approval.response` | Resolve a pending approval request |
| `file.read` | Read a file relative to the session workspace or by absolute path |

## Session Content

`session.content` returns:

```json
{
  "session_id": "SESSION_ID",
  "thread_id": "THREAD_ID",
  "last_seq": 123,
  "session": {},
  "entries": [],
  "active_turns": [],
  "pending_approvals": []
}
```

The Android client treats this as the source of truth for history.

## Incremental Sync

`session.sync` request:

```json
{
  "id": "sync-1",
  "type": "session.sync",
  "payload": {
    "session_id": "SESSION_ID",
    "since_seq": 123,
    "incremental": true
  }
}
```

`session.sync` response:

```json
{
  "session_id": "SESSION_ID",
  "thread_id": "THREAD_ID",
  "last_seq": 130,
  "entries": [],
  "active_turns": [],
  "pending_approvals": [],
  "changed_turn_ids": [],
  "needs_full_sync": false,
  "fallback_reason": ""
}
```

See [Session Incremental Sync](session-incremental-sync.md) for merge rules.

## File Reads

`file.read` request:

```json
{
  "id": "file-1",
  "type": "file.read",
  "payload": {
    "session_id": "SESSION_ID",
    "path": "src/index.mjs",
    "max_bytes": 65536
  }
}
```

The response payload contains:

```json
{
  "session_id": "SESSION_ID",
  "path": "src/index.mjs",
  "resolved_path": "/workspace/src/index.mjs",
  "content": "...",
  "truncated": false,
  "bytes": 1234
}
```

Relative paths are resolved from the session `cwd`. Absolute paths are read directly.

## Common Events

The bridge forwards Codex method names in the `event` field. Common events include:

- `thread/started`
- `turn/started`
- `item/agentMessage/delta`
- `item/completed`
- `item/commandExecution/requestApproval`
- `item/fileChange/requestApproval`
- `item/permissions/requestApproval`
- `serverRequest/resolved`
- `thread/tokenUsage/updated`
- `turn/completed`
- `session/changed`
