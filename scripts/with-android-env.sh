#!/usr/bin/env bash
# Load local Android/JDK paths, then run a command (or print exports).
#
# Usage:
#   ./scripts/with-android-env.sh ./gradlew test
#   source scripts/with-android-env.sh    # export into current shell
#   ./scripts/with-android-env.sh         # print export lines
#
# Resolution order for JAVA_HOME / ANDROID_HOME:
#   1. Already set in the environment
#   2. scripts/env.local.sh (gitignored; copy from env.local.sh.example)
#   3. $HOME/Android/jdk-17 and $HOME/Android/Sdk
#
# Sourced mode never changes the caller's shell options: unlike the executed
# mode, it does not enable `set -euo pipefail`, so a missing JDK/SDK prints an
# error and returns 1 without terminating the caller's shell. Note that if the
# caller itself runs with `set -e`, a plain `source scripts/with-android-env.sh`
# failure still aborts it — guard with `source ... || echo "env load failed"`.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_ENV="$ROOT/scripts/env.local.sh"

_pixelify_load_env() {
  if [[ -f "$LOCAL_ENV" ]]; then
    # shellcheck disable=SC1090
    source "$LOCAL_ENV"
  fi

  if [[ -z "${JAVA_HOME:-}" ]]; then
    export JAVA_HOME="$HOME/Android/jdk-17"
  fi
  if [[ -z "${ANDROID_HOME:-}" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
  export PATH="$JAVA_HOME/bin:${PATH:-}"

  if [[ ! -x "${JAVA_HOME}/bin/java" ]]; then
    echo "error: java not found under JAVA_HOME=$JAVA_HOME" >&2
    echo "Set JAVA_HOME, or copy scripts/env.local.sh.example → scripts/env.local.sh" >&2
    return 1
  fi
  if [[ ! -d "${ANDROID_HOME}" ]]; then
    echo "error: ANDROID_HOME directory missing: $ANDROID_HOME" >&2
    echo "Set ANDROID_HOME, or create ignored local.properties with sdk.dir=..." >&2
    return 1
  fi
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  # Executed: fail fast on any error.
  set -euo pipefail
  _pixelify_load_env

  if [[ $# -eq 0 ]]; then
    printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
    printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
    printf 'export ANDROID_SDK_ROOT=%q\n' "$ANDROID_SDK_ROOT"
    printf 'export PATH=%q\n' "$JAVA_HOME/bin:$PATH"
    exit 0
  fi

  exec "$@"
else
  # Sourced: export into the caller without altering its shell options.
  _pixelify_load_env || return $?
  return 0
fi
