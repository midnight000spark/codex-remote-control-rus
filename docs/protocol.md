# Bridge Protocol

The bridge exposes a small HTTP surface and a token-protected WebSocket protocol.

## HTTP

`GET /healthz`

```json
{ "ok": true, "name": "codex-remote-control", "version": "<package.json version>" }
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
    "version": "<package.json version>",
    "server_time": "2026-05-24T00:00:00.000Z"
  }
}
```

## Connection Health

The bridge pings every client socket every 30 seconds. A socket that does not
answer with a pong within one interval is terminated, so a phone that slept or
lost its network does not stay registered as a connection.

Clients should answer pings themselves; the Android client relies on the
WebSocket stack doing that. The bridge also answers client `ping` messages with
a `pong` response.

The bridge never lets a slow client buffer the whole session history: if a
socket accumulates more than 4 MiB of unsent frames it is closed with code
`1009` (`client too slow`). Reconnect and re-sync with `session.sync` instead of
expecting that socket to recover.

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

## Field Naming

Two rules cover almost everything:

- **Identifiers** are `snake_case`: `session_id`, `thread_id`, `turn_id`,
  `request_id`, `since_seq`, `max_bytes`. `thread_id` is accepted anywhere a
  session is resolved.
- **Session and turn settings** are `camelCase`: `approvalPolicy`,
  `approvalsReviewer`, `sandboxPolicy`, `modelProvider`, `serviceTier`,
  `personality`, `persistExtendedHistory`, `baseInstructions`,
  `developerInstructions`, `threadSource`, `sessionStartSource`,
  `responsesapiClientMetadata`, `strictAutoReview`.

For the settings above, the bridge accepts either spelling: a snake_case
setting (`approval_policy`, `sandbox_policy`, `base_instructions`, ...) is
aliased to its camelCase name before dispatch, and an explicit camelCase key
wins if both are present. Identifiers are not aliased - `session_id` is not
read as `sessionId`. `session.update` also treats `sandbox` and `sandboxPolicy`
as the same field, and `turn.interrupt` reads `turn_id ?? turnId`.

Event envelopes written by the bridge are always `snake_case` (`seq`, `at`,
`event`, `session_id`, `turn_id`). The nested `payload` of an event mirrors
whatever the Codex backend sent, so its keys may be `snake_case` or `camelCase`
(`threadId` / `thread_id`, `turnId` / `turn_id`). Clients must read both
spellings for backend-provided fields and use the envelope only for ordering and
session attribution.

### Request payload keys

Settings are listed in their canonical camelCase form; the snake_case alias
works as well.

| Message | Keys |
| --- | --- |
| `session.list` | `history_months` / `historyMonths` |
| `session.start` | `cwd`, `model`, `approvalPolicy`, `approvalsReviewer`, `sandbox`, `permissions`, `config`, `serviceName`, `baseInstructions`, `developerInstructions`, `personality`, `ephemeral`, `sessionStartSource`, `threadSource`, `environments`, `dynamicTools`, `mockExperimentalField`, `experimentalRawEvents`, `persistExtendedHistory` |
| `session.resume` | `session_id` / `thread_id`, `history`, `path`, `model`, `modelProvider`, `serviceTier`, `cwd`, `approvalPolicy`, `approvalsReviewer`, `sandbox`, `permissions`, `config`, `baseInstructions`, `developerInstructions`, `personality`, `excludeTurns`, `persistExtendedHistory` |
| `session.update` | `session_id` / `thread_id`, `approvalPolicy`, `permissions`, `sandbox` / `sandboxPolicy` |
| `turn.send` | `session_id` / `thread_id`, `text`, `input`, `responsesapiClientMetadata`, `environments`, `cwd`, `approvalPolicy`, `approvalsReviewer`, `sandboxPolicy`, `permissions`, `model`, `serviceTier`, `effort`, `summary`, `personality`, `outputSchema`, `collaborationMode`, `turn_id` |
| `turn.steer` | `session_id` / `thread_id`, `turn_id`, `text`, `input`, `responsesapiClientMetadata` |
| `turn.interrupt` | `session_id` / `thread_id`, `turn_id` / `turnId` |
| `approval.response` | `request_id` / `requestId`, `session_id` / `thread_id`, `decision`, `permissions`, `scope`, `strictAutoReview`, `result`, `error` |
| `file.read` | `session_id` / `thread_id`, `path`, `max_bytes` |

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
| `file.read` | Read a file inside the session workspace or the bridge uploads directory |

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
  "name": "index.mjs",
  "mime_type": "text/javascript",
  "content_kind": "text",
  "encoding": "utf-8",
  "content": "...",
  "truncated": false,
  "bytes": 1234,
  "size": 1234,
  "downloadable": true
}
```

`content_kind` is one of `text`, `image`, `pdf`, `audio`, `video`, or `binary`. Only text files include
preview content in the WebSocket response. `bytes` is the UTF-8 preview size, while `size` is the full
file size. Relative paths are resolved from the session `cwd`.

Absolute paths are accepted only when they resolve inside the session workspace or inside the bridge's
own uploads directory (`<state dir>/uploads`). Resolution uses `realpath`, so a symlink whose target
leaves the workspace is rejected as well. Rejected reads fail with the `PATH_OUTSIDE_SESSION` code.

Authenticated clients can stream the complete file without loading it into the WebSocket payload:

```text
GET /v1/file?session_id=SESSION_ID&path=src%2Findex.mjs&token=BRIDGE_TOKEN
```

The response includes the detected `Content-Type`, full `Content-Length`, and an inline
`Content-Disposition` filename. The `x-bridge-token` request header may be used instead of the query token.

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
