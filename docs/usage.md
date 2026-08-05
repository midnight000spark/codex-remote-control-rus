# Usage

This guide covers the local bridge and Android APK workflow.

## Requirements

- Node.js 20 or newer
- npm
- Codex CLI available as `codex` when using the real backend
- Android SDK and a JDK for APK builds
- A trusted private network between the phone and bridge host

The checked-in Gradle wrapper uses Gradle 8.13. The Android app targets SDK 36 and has a minimum SDK of 26.

## Install

```bash
npm install
```

## Run The Bridge

The default bridge launcher starts the real Codex backend and listens on all interfaces:

```bash
npm run bridge
```

It writes local state and token files under `~/.config/codex_remote_control/` by default, then prints WebSocket URLs that can be pasted into the Android app.

Default files:

- `~/.config/codex_remote_control/bridge-state.json`
- `~/.config/codex_remote_control/bridge-token.txt`
- `~/.config/codex_remote_control/bridge-id.txt`

The bridge URL format is:

```text
ws://HOST:PORT/?token=TOKEN
```

If Codex needs a local proxy, enable it before starting the bridge:

```bash
source ~/.zshrc && proxy_on
npm run bridge
```

## Bridge Environment Variables

`scripts/run-bridge.sh` accepts these environment variables:

| Variable | Default | Description |
| --- | --- | --- |
| `CODEX_REMOTE_HOST` | `0.0.0.0` | Bridge listen host |
| `CODEX_REMOTE_PORT` | `8787` | Bridge listen port |
| `CODEX_REMOTE_BACKEND` | `codex` | `codex` or `mock` |
| `CODEX_REMOTE_DATA_DIR` | `~/.config/codex_remote_control` | Base directory for bridge runtime files |
| `CODEX_REMOTE_TOKEN_FILE` | `~/.config/codex_remote_control/bridge-token.txt` | Token file path |
| `CODEX_REMOTE_STATE_FILE` | `~/.config/codex_remote_control/bridge-state.json` | State file path |
| `CODEX_REMOTE_CLEAR_STATE_ON_START` | `0` | Set `1`, `true`, or `yes` to clear local bridge history before startup |
| `CODEX_REMOTE_SYNC_LOG` | `0` | Set `1`, `true`, or `yes` to enable sync logging |
| `CODEX_REMOTE_SYNC_LOG_FILE` | `~/.config/codex_remote_control/bridge-sync.log` | Sync log file path |

Example using the mock backend:

```bash
CODEX_REMOTE_BACKEND=mock npm run bridge
```

Example using another port:

```bash
CODEX_REMOTE_PORT=8790 npm run bridge
```

Example clearing bridge-side local history on startup:

```bash
CODEX_REMOTE_CLEAR_STATE_ON_START=1 npm run bridge
```

## Direct Bridge Command

The Node entrypoint can be run directly:

```bash
node src/index.mjs \
  --backend codex \
  --listen 0.0.0.0:8787 \
  --state ~/.config/codex_remote_control/bridge-state.json \
  --token-file ~/.config/codex_remote_control/bridge-token.txt \
  --clear-state-on-start
```

Useful options:

- `--backend mock|codex`
- `--listen HOST:PORT`
- `--state PATH`
- `--token-file PATH`
- `--clear-state-on-start`
- `--sync-log PATH`
- `--protocol-log PATH`
- `--codex-bin PATH`
- `--codex-arg VALUE`
- `--codex-config KEY=VALUE`

`--clear-state-on-start` only clears the bridge state file. It does not change the token file or bridge id file.

`--protocol-log` records raw bridge-to-Codex protocol lines and may contain sensitive content. Prefer a temporary path and delete it after debugging.

## Android APK

Build the debug APK:

```bash
npm run apk:release:setup # one time per signing key
npm run apk
```

Output:

```text
android/app/build/outputs/apk/debug/codex-remote-control-v0.2.0-debug.apk
```

Build the release APK:

```bash
npm run apk:release
```

Output:

```text
android/app/build/outputs/apk/release/codex-remote-control-v0.2.0-release.apk
```

Debug and release APKs are signed with the same local personal key in `android/release.keystore`.
Keep `android/release.keystore` and `android/release-signing.properties` backed up securely; future upgrades must use the same key.

You can also open `android/` in Android Studio.

The app uses `ws://` cleartext traffic so it can connect to a private-network bridge. Do not expose the bridge or the APK as a hardened public-internet deployment.

## Debug Logs

Android session sync logs are disabled by default:

```bash
adb shell setprop log.tag.CodexRemoteSync DEBUG
adb logcat -s CodexRemoteSync:D
```

Disable them again:

```bash
adb shell setprop log.tag.CodexRemoteSync INFO
```

Android approval logs:

```bash
adb shell setprop log.tag.CodexRemoteApproval DEBUG
adb logcat -s CodexRemoteApproval:D
```

Disable:

```bash
adb shell setprop log.tag.CodexRemoteApproval INFO
```

Bridge sync logging:

```bash
CODEX_REMOTE_SYNC_LOG=1 npm run bridge
```

Bridge protocol logging:

```bash
node src/index.mjs \
  --backend codex \
  --listen 0.0.0.0:8787 \
  --state ~/.config/codex_remote_control/bridge-state.json \
  --token-file ~/.config/codex_remote_control/bridge-token.txt \
  --protocol-log /tmp/codex-remote-protocol.log
```
