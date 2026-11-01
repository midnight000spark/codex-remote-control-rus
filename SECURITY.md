# Security Policy

## Supported Use

Codex Remote Control is intended for trusted private networks. The bridge can control local Codex
sessions, read workspace files through `file.read`, and forward approvals that may allow commands or
file changes.

Do not expose the bridge directly to the public internet.

## Authentication

Every WebSocket client and every `/v1/file` or `/v1/image` request must present the bridge token,
either as `?token=` or as the `x-bridge-token` header. `/healthz` is the only anonymous endpoint.

- The launcher stores the token in `~/.config/codex_remote_control/bridge-token.txt` with mode `0600`.
- An empty token accepts every client. The bridge refuses to start on a non-loopback address in that
  case unless `--insecure` is passed explicitly, in which case it logs a warning.
- Rotate the token if a phone, log, or shell history may have leaked it. Deleting the token file makes
  the launcher generate a new one on the next start.

## Network Exposure

The bridge is designed for a private overlay network such as Tailscale.

- `scripts/run-bridge.sh` binds `0.0.0.0` by default but refuses to start when no `100.64.0.0/10`
  address is present, unless `CODEX_REMOTE_ALLOW_PUBLIC=1` is set.
- For a local-only bridge use `CODEX_REMOTE_HOST=127.0.0.1`.
- TLS is not implemented. Keep the bridge on a trusted overlay or behind a reverse proxy that
  terminates TLS.

## File Access

- `file.read`, `GET /v1/file`, and `GET /v1/image` resolve the requested path with `realpath` and
  reject anything outside the session workspace, except the bridge-managed uploads directory
  (`<state dir>/uploads`), which only the bridge writes to.
- Symlinks that point out of the workspace are rejected with `PATH_OUTSIDE_SESSION` (`403` over HTTP).
- Relative paths are resolved from the session `cwd`. A session without a `cwd` accepts absolute paths
  only.

## Approvals

Approval responses are forwarded only for a request id that is currently pending for that session, and
only with a decision the backend offered. Unknown ids and unsupported decisions are rejected instead of
being passed through.

## Sandbox Default

New sessions use `danger-full-access` unless `CODEX_REMOTE_DEFAULT_SANDBOX` is set to
`workspace-write` or `read-only`. An unparsable value aborts startup instead of silently falling back
to full access.

## Logs and Local Files

- The sync log redacts the `token` query parameter and never echoes raw malformed payloads.
- `--protocol-log` may contain prompts, paths, session ids, and full protocol payloads. Prefer a
  temporary location for it.
- Bridge state is persisted with mode `0600` and should stay out of the repository.
- The debug APK is a private development build, not a distributable release.

## Reporting

This repository does not currently publish a dedicated vulnerability contact. If it is made public on
GitHub, use GitHub issues for non-sensitive security hardening discussion and configure private
vulnerability reporting before accepting sensitive reports.