#!/usr/bin/env bash
# Pick a full JDK (one with javac) for Gradle.
#
# Android Gradle Plugin needs a JDK, not a JRE. Some distributions ship
# /usr/lib/jvm/java-21-openjdk-amd64 as a JRE-only package, and Gradle then
# fails with "does not provide the required capabilities: [JAVA_COMPILER]".
# Respect an explicit JAVA_HOME that has javac, otherwise fall back to the
# usual install roots.
set -euo pipefail

has_compiler() {
  [[ -n "${1:-}" && -x "$1/bin/javac" ]]
}

pick_jdk() {
  local root candidate
  for root in /usr/lib/jvm "$HOME/.jdks" "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java"; do
    [[ -d "$root" ]] || continue
    # Java 17 is what the project is built and tested with; accept any other
    # full JDK only as a fallback.
    for candidate in "$root"/*17* "$root"/*21* "$root"/*; do
      if has_compiler "$candidate"; then
        printf '%s\n' "$candidate"
        return 0
      fi
    done
  done
  return 1
}

if ! has_compiler "${JAVA_HOME:-}"; then
  if selected="$(pick_jdk)"; then
    export JAVA_HOME="$selected"
    echo "Using JDK: $JAVA_HOME" >&2
  else
    echo "No JDK with javac found. Install JDK 17+ or set JAVA_HOME to a full JDK." >&2
    exit 1
  fi
fi