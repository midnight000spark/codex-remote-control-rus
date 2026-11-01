#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=android/scripts/jdk.sh
source "$ROOT_DIR/android/scripts/jdk.sh"
SIGNING_CONFIG="$ROOT_DIR/android/release-signing.properties"
RELEASE_DIR="$ROOT_DIR/android/app/build/outputs/apk/release"
VERSION="$(node -p "require('$ROOT_DIR/package.json').version")"
SIGNED_APK="$RELEASE_DIR/codex-remote-control-v${VERSION}-release.apk"
LOCAL_MAVEN_CACHE="$ROOT_DIR/android/build/local-maven-cache"
INIT_SCRIPT=""

cleanup() {
  if [[ -n "$INIT_SCRIPT" && -f "$INIT_SCRIPT" ]]; then
    rm -f "$INIT_SCRIPT"
  fi
}

trap cleanup EXIT

if [[ ! -f "$SIGNING_CONFIG" ]]; then
  echo "Missing release signing config: $SIGNING_CONFIG" >&2
  echo "Run: bash android/scripts/create-release-keystore.sh" >&2
  exit 1
fi

GRADLE_ARGS=(assembleRelease)

if [[ -d "$LOCAL_MAVEN_CACHE" ]] && find "$LOCAL_MAVEN_CACHE" -type f | grep -q .; then
  INIT_SCRIPT="$ROOT_DIR/android/build/local-maven-cache.init.gradle"
  cat > "$INIT_SCRIPT" <<EOF
import org.gradle.api.initialization.resolve.RepositoriesMode
settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    settings.dependencyResolutionManagement.repositories.clear()
    settings.dependencyResolutionManagement.repositories.maven { url = uri('$LOCAL_MAVEN_CACHE') }
    settings.dependencyResolutionManagement.repositories.google()
    settings.dependencyResolutionManagement.repositories.mavenCentral()
}
EOF
  echo "Using local Maven cache: $LOCAL_MAVEN_CACHE"
  GRADLE_ARGS=(--init-script "$INIT_SCRIPT" assembleRelease)
fi

cd "$ROOT_DIR/android"
./gradlew "${GRADLE_ARGS[@]}"

if [[ ! -f "$SIGNED_APK" ]]; then
  echo "Expected signed release APK was not produced: $SIGNED_APK" >&2
  exit 1
fi

echo "Release APK ready: $SIGNED_APK"
