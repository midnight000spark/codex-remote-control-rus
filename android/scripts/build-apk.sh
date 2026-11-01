#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=android/scripts/jdk.sh
source "$ROOT_DIR/android/scripts/jdk.sh"
DEBUG_DIR="$ROOT_DIR/android/app/build/outputs/apk/debug"
VERSION="$(node -p "require('$ROOT_DIR/package.json').version")"
DEBUG_APK="$DEBUG_DIR/codex-remote-control-v${VERSION}-debug.apk"

cd "$ROOT_DIR/android"
./gradlew assembleDebug

if [[ ! -f "$DEBUG_APK" ]]; then
  echo "Expected debug APK was not produced: $DEBUG_APK" >&2
  exit 1
fi

echo "Debug APK ready: $DEBUG_APK"
