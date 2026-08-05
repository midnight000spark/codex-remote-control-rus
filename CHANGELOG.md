# Changelog

[中文更新日志](CHANGELOG.zh-CN.md)

## v0.2.0 - 2026-08-05

This release expands the Android client from basic remote chat into a more complete mobile companion for active Codex sessions.

### Added

- Collaboration mode, thread goal, model, reasoning effort, sandbox, and token status controls in the chat composer.
- Image upload and multi-image message sending from Android, plus authenticated image rendering, zooming, copying, and saving.
- Authenticated file downloads with image preview, binary file open/save support, and APK installation guidance.
- Markdown and restricted static HTML previews with source/rendered view switching and relative image support.
- Background, cross-session, approval, connection-failure, and task-completion notifications.
- Optional Beijing-time message timestamps with persisted and backup-aware settings.
- Remote project directory browsing and selection when starting a new session.

### Changed

- The bridge now prefers the highest available Codex binary version while preserving an explicit `--codex-bin` override.
- Session list payloads are smaller, support configurable history windows, and can refresh automatically when the drawer opens.
- Desktop rollout monitoring now reads appended data incrementally, handles incomplete lines and file rotation, and deduplicates realtime events.
- The Android release build can reuse a populated local Maven cache when available.

### Fixed

- Kept model, reasoning effort, sandbox selection, and current-session status synchronized across session creation, switching, refresh, and restore.
- Persisted the model and reasoning effort used by successful sends even when backend thread summaries lag behind.
- Restored newline entry in the chat composer.
- Stabilized user-message identifiers so realtime projections reconcile cleanly with authoritative session snapshots.
- Persisted and surfaced Codex backend connection failures with actionable network guidance.

### Release Artifacts

- Android debug APK: `android/app/build/outputs/apk/debug/codex-remote-control-v0.2.0-debug.apk`
- Android release APK: `android/app/build/outputs/apk/release/codex-remote-control-v0.2.0-release.apk`

### Notes

- Existing installations must continue using the same `android/release.keystore` and `android/release-signing.properties` to upgrade in place.
- Android 13 and newer require notification permission before background notifications can be shown.
- Background delivery remains best effort with the current activity-owned WebSocket lifecycle; see `docs/android-background-connection.md`.

## v0.1.0 - 2026-05-27

First formal release of Codex Remote Control.

### Added

- Android client for connecting to a private-network Codex bridge.
- Session list, session resume, new chat, message sending, and streaming assistant output.
- Approval request display and response forwarding from Android.
- Session-authoritative conversation rendering with snapshot reconciliation and incremental updates.
- Code browser and diff/file-change viewing from active Codex workspaces.
- Shared bridge cache, connection history, reconnect feedback, and settings controls.
- Node.js WebSocket bridge for `codex app-server --listen stdio://`.
- Persistent bridge identity, state storage, token handling, and configurable runtime paths.
- Mock backend and Node-side tests for bridge and Android UI development flows.
- Debug and release APK build helpers with shared local personal signing.

### Release Artifacts

- Android debug APK: `android/app/build/outputs/apk/debug/codex-remote-control-v0.1.0-debug.apk`
- Android release APK: `android/app/build/outputs/apk/release/codex-remote-control-v0.1.0-release.apk`

### Notes

- Debug and release APKs are signed with the same local personal key created by `npm run apk:release:setup`.
- The bridge and app are designed for trusted private networks, not direct public internet exposure.
