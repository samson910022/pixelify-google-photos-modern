#!/usr/bin/env bash
# Harden a JDK truststore for CI/local JVM TLS.
#
# The selected JDK is never modified. A private copy of lib/security/cacerts is
# hardened by removing the public eMudhra incident trust anchors (matched by
# SPKI SHA-256), a public canary anchor is added, and JAVA_TOOL_OPTIONS is
# configured to use the hardened copy. The canary lets --verify-only prove that
# the configured copy is actually the effective JSSE default truststore.
#
# Usage:
#   scripts/harden-jdk-truststore.sh [options]
#
# Modes:
#   (default)          Harden a copy of the selected JDK's cacerts.
#   --verify-only      Verify the effective JSSE truststore configured by
#                      JAVA_TOOL_OPTIONS, without needing the out-store path.
#   --check-only       Check the selected JDK's lib/security/cacerts read-only
#                      and print guidance if incident anchors are still
#                      present. This ignores JAVA_TOOL_OPTIONS; use
#                      --verify-only for the effective store.
#   --probe-tls        Require the effective JSSE truststore configured by
#                      JAVA_TOOL_OPTIONS (same requirements as --verify-only),
#                      verify it, then probe the default HTTPS endpoints.
#
# Harden options:
#   --java-home DIR    JDK to copy cacerts from (default: $JAVA_HOME).
#   --out-store FILE   Hardened store path (default: a new mktemp dir/cacerts;
#                      the parent directory must already exist).
#   --out-env FILE     Append JAVA_TOOL_OPTIONS=... to a GitHub env file.
#   --print-env        Print a shell-quoted export for the current shell.
#   --help             Show this help.
#
# Output: default mode prints the tool JSON followed by the
# JAVA_TOOL_OPTIONS=... line to stdout. --out-env writes only the JSON to
# stdout and appends the assignment to a GitHub Actions env file (not for shell
# sourcing). --print-env is eval-safe: the JSON goes to stderr and stdout holds
# only the shell-quoted export. The store password is the public JDK default
# "changeit"; it is not a secret. Only JKS and PKCS12 stores are supported.
set -euo pipefail

umask 077

# Resolve the real script path so invocation through a symlink (for example a
# bin/harden -> scripts/harden-jdk-truststore.sh shim) still finds the Java
# tool, pins, and canary beside the real file instead of beside the symlink.
resolve_script_path() {
  local path="$1"
  local directory
  local hops=0
  while [[ -L "$path" ]]; do
    hops=$((hops + 1))
    if [[ "$hops" -gt 40 ]]; then
      printf 'ERROR: too many symbolic links while resolving %s\n' "$1" >&2
      return 1
    fi
    directory="$(cd -- "$(dirname -- "$path")" && pwd -P)" || return 1
    path="$(readlink -- "$path")" || return 1
    [[ "$path" == /* ]] || path="$directory/$path"
  done
  printf '%s\n' "$path"
}

SCRIPT_PATH="$(resolve_script_path "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(cd -- "$(dirname -- "$SCRIPT_PATH")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
TOOL="$SCRIPT_DIR/jdk-truststore/TrustStoreTool.java"
PINS="$SCRIPT_DIR/jdk-truststore/pins.txt"
CANARY="$SCRIPT_DIR/jdk-truststore/ci-canary-cert.txt"
DEFAULT_CANARY_ALIAS="pixelify-ci-truststore-canary"
# Safety floor for the real JDK cacerts copy. The pin list is the removal
# policy; this only catches catastrophic loss of unrelated anchors.
MIN_CERTS=50

usage() {
  cat <<'EOF'
Harden a JDK truststore for CI/local JVM TLS.

Usage:
  scripts/harden-jdk-truststore.sh [options]

Modes:
  (default)          Harden a copy of the selected JDK's cacerts.
  --verify-only      Verify the effective JSSE truststore configured by
                     JAVA_TOOL_OPTIONS (use this for the hardened store).
  --check-only       Check the selected JDK's lib/security/cacerts read-only
                     and print guidance if incident anchors are still present.
                     This inspects only the selected JDK's cacerts and ignores
                     JAVA_TOOL_OPTIONS.
  --probe-tls        Require the effective JSSE truststore configured by
                     JAVA_TOOL_OPTIONS (same requirements as --verify-only),
                     verify it, then probe the default HTTPS endpoints.

Harden options:
  --java-home DIR    JDK to copy cacerts from (default: $JAVA_HOME).
  --out-store FILE   Hardened store path (default: a new mktemp dir/cacerts).
                     The parent directory must already exist.
  --out-env FILE     Append JAVA_TOOL_OPTIONS=... to a GitHub env file.
  --print-env        Print a shell-quoted export for the current shell.
  --help             Show this help.

Safety:
  --out-store and --out-env are resolved with realpath, including parent
  directories, and must be outside the repository and outside JAVA_HOME.
  Their parent directories must already exist; the wrapper does not create
  them. --out-env must name a regular file; a directory, FIFO, socket, device,
  or symlink is refused, and it must not be the JDK cacerts or the same file as
  --out-store. The final path component must not be a symlink, and an existing
  output must not be a hardlink to a repository file. Both destinations are
  staged beside the final path and are only replaced after the hardening tool
  succeeds, using GNU mv -T so a symlink swapped in mid-run is replaced instead
  of followed. Their parent directories are pinned with a directory file
  descriptor and all staging and installation goes through /proc/self/fd, so
  renaming or replacing a parent directory during hardening cannot redirect a
  write. This requires Linux /proc/self/fd;
  the wrapper fails closed where it is unavailable.

Output:
  Without --out-env or --print-env, stdout receives the tool JSON followed by
  the JAVA_TOOL_OPTIONS=... line. With --out-env, stdout receives only the tool
  JSON and JAVA_TOOL_OPTIONS=... is written to the GitHub Actions env file;
  --out-env is not for shell sourcing. --print-env is eval-safe: the tool JSON
  goes to stderr and stdout contains only the shell-quoted export line.

The selected JDK is never modified. The store password is the public JDK
default "changeit"; it is not a secret. Only JKS and PKCS12 stores are
supported.
EOF
}

error() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_file() {
  local path="$1"
  local description="$2"
  [[ -f "$path" && -r "$path" ]] || error "$description is missing or unreadable: $path"
}

same_file() {
  [[ -e "$1" && -e "$2" ]] || return 1
  [[ "$(stat -c '%d:%i' -- "$1")" == "$(stat -c '%d:%i' -- "$2")" ]]
}

# Verify that an open directory file descriptor still names the canonical
# directory that was validated. /proc/self/fd/N is a magic link to the opened
# inode, so staging and installation through it cannot be redirected by a
# rename or symlink swap of a parent component. readlink -f additionally
# catches a canonical path that now resolves elsewhere; the device/inode
# comparison catches a canonical path that was renamed away and no longer
# exists (readlink -f would otherwise echo the stale path).
dir_fd_resolves_to() {
  local fd_path="$1"
  local canonical="$2"
  local resolved
  resolved="$(readlink -f -- "$fd_path")" || return 1
  [[ "$resolved" == "$canonical" ]] || return 1
  [[ -d "$canonical" ]] || return 1
  local fd_id
  local canonical_id
  fd_id="$(stat -Lc '%d:%i' -- "$fd_path")" || return 1
  canonical_id="$(stat -Lc '%d:%i' -- "$canonical")" || return 1
  [[ "$fd_id" == "$canonical_id" ]]
}

refuse_inside_repo() {
  local description="$1"
  local path="$2"
  if [[ "$path" == "$REPO_ROOT" || "$path" == "$REPO_ROOT/"* ]]; then
    error "refusing to write $description inside the repository ($REPO_ROOT): $path"
  fi
}

refuse_repo_hardlink() {
  local description="$1"
  local path="$2"
  [[ -e "$path" ]] || return 0
  # Do not open the candidate: GNU find -samefile opens it, which blocks on a
  # FIFO. Compare device and inode numbers instead. Hardlinks cannot cross
  # filesystems, so a candidate on another device cannot alias a repo file.
  local candidate_device
  local repo_device
  candidate_device="$(stat -c '%d' -- "$path")" \
    || error "cannot stat $description candidate: $path"
  repo_device="$(stat -c '%d' -- "$REPO_ROOT")" \
    || error "cannot stat repository root: $REPO_ROOT"
  if [[ "$candidate_device" != "$repo_device" ]]; then
    return 0
  fi
  local candidate_inode
  local linked
  candidate_inode="$(stat -c '%i' -- "$path")" \
    || error "cannot stat $description candidate: $path"
  linked="$(find "$REPO_ROOT" -xdev -type f -inum "$candidate_inode" -print -quit)" \
    || error "cannot scan the repository for hardlinks to $description: $path"
  if [[ -n "$linked" ]]; then
    error "refusing to write $description that is a hardlink to a repository file ($linked): $path"
  fi
}

resolve_java_home() {
  local candidate="${java_home_arg:-${JAVA_HOME:-}}"
  [[ -n "$candidate" ]] || error "JAVA_HOME is not set; pass --java-home DIR or export JAVA_HOME"
  [[ -d "$candidate" ]] || error "JAVA_HOME is not a directory: $candidate"
  local resolved
  resolved="$(cd -- "$candidate" && pwd -P)" || error "cannot resolve JAVA_HOME: $candidate"
  [[ -x "$resolved/bin/java" ]] || error "java not found under JAVA_HOME: $resolved/bin/java"
  [[ -f "$resolved/lib/security/cacerts" && -r "$resolved/lib/security/cacerts" ]] \
    || error "JDK cacerts is missing or unreadable: $resolved/lib/security/cacerts"
  printf '%s\n' "$resolved"
}

detect_store_type() {
  local store="$1"
  local magic
  magic="$(od -An -tx1 -N4 -- "$store" | tr -d ' \n')"
  case "$magic" in
    feedfeed)
      printf 'JKS\n'
      ;;
    30*)
      printf 'PKCS12\n'
      ;;
    *)
      error "unrecognized truststore magic 0x${magic:-<empty>} in $store (expected JKS FEEDFEED or PKCS12 DER 30)"
      ;;
  esac
}

# Return the effective -Djavax.net.ssl.trustStore value. The JVM applies the
# last duplicate -D definition, so scan every occurrence (quoted or unquoted)
# and keep the final one instead of matching only the first.
extract_truststore_path() {
  local remaining="$1"
  local marker="-Djavax.net.ssl.trustStore="
  local path=""
  while [[ "$remaining" == *"$marker"* ]]; do
    remaining="${remaining#*"$marker"}"
    local value
    if [[ "$remaining" == \"* ]]; then
      local rest="${remaining#\"}"
      value="${rest%%\"*}"
      if [[ "$rest" == *\"* ]]; then
        remaining="${rest#*\"}"
      else
        remaining=""
      fi
    else
      value="${remaining%%[[:space:]]*}"
      if [[ "$remaining" == *[[:space:]]* ]]; then
        remaining="${remaining#*[[:space:]]}"
      else
        remaining=""
      fi
    fi
    path="$value"
  done
  printf '%s' "$path"
}

mode="harden"
java_home_arg=""
out_store_arg=""
out_store_set=0
out_env=""
out_env_set=0
print_env=0

select_mode() {
  local requested="$1"
  if [[ "$mode" != "harden" && "$mode" != "$requested" ]]; then
    error "--$requested cannot be combined with --$mode"
  fi
  mode="$requested"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --java-home)
      [[ $# -ge 2 ]] || error "--java-home requires a value"
      java_home_arg="$2"
      shift 2
      ;;
    --java-home=*)
      java_home_arg="${1#*=}"
      shift
      ;;
    --out-store)
      [[ $# -ge 2 ]] || error "--out-store requires a value"
      out_store_arg="$2"
      out_store_set=1
      shift 2
      ;;
    --out-store=*)
      out_store_arg="${1#*=}"
      out_store_set=1
      shift
      ;;
    --out-env)
      [[ $# -ge 2 ]] || error "--out-env requires a value"
      out_env="$2"
      out_env_set=1
      shift 2
      ;;
    --out-env=*)
      out_env="${1#*=}"
      out_env_set=1
      shift
      ;;
    --print-env)
      print_env=1
      shift
      ;;
    --verify-only)
      select_mode "verify-only"
      shift
      ;;
    --check-only)
      select_mode "check-only"
      shift
      ;;
    --probe-tls)
      select_mode "probe-tls"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      error "unknown option: $1 (try --help)"
      ;;
  esac
done

if [[ "$out_store_set" -eq 1 && -z "$out_store_arg" ]]; then
  error "--out-store requires a non-empty path"
fi
if [[ "$out_env_set" -eq 1 && -z "$out_env" ]]; then
  error "--out-env requires a non-empty path"
fi
if [[ "$mode" != "harden" ]]; then
  [[ "$out_store_set" -eq 0 ]] || error "--out-store is only valid when hardening"
  [[ "$out_env_set" -eq 0 ]] || error "--out-env is only valid when hardening"
  [[ "$print_env" -eq 0 ]] || error "--print-env is only valid when hardening"
fi
if [[ "$out_env_set" -eq 1 && "$print_env" -eq 1 ]]; then
  error "--out-env and --print-env are mutually exclusive"
fi

jdk_home="$(resolve_java_home)"
source_store="$jdk_home/lib/security/cacerts"
java_bin="$jdk_home/bin/java"

require_file "$TOOL" "truststore tool"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/pixelify-jdk-truststore.XXXXXX")"
keep_work_dir=0
STAGING_FILE=""
ENV_STAGING_FILE=""
OUT_DIR_FD=""
ENV_DIR_FD=""
cleanup() {
  # Staging paths may be /proc/self/fd/N/...; remove them while the pinned
  # directory descriptors are still open, then close the descriptors.
  if [[ -n "${STAGING_FILE:-}" ]]; then
    rm -f -- "$STAGING_FILE"
  fi
  if [[ -n "${ENV_STAGING_FILE:-}" ]]; then
    rm -f -- "$ENV_STAGING_FILE"
  fi
  if [[ -n "${OUT_DIR_FD:-}" ]]; then
    exec {OUT_DIR_FD}<&- || true
  fi
  if [[ -n "${ENV_DIR_FD:-}" ]]; then
    exec {ENV_DIR_FD}<&- || true
  fi
  if [[ "$keep_work_dir" -ne 1 ]]; then
    rm -rf -- "$work_dir"
  fi
}
trap cleanup EXIT

# Write JAVA_TOOL_OPTIONS=... to a GitHub Actions env file without following a
# destination symlink that is swapped in after the up-front checks. The new
# content is staged beside the destination through the pinned directory file
# descriptor, the pinned destination is re-checked, and GNU mv -T replaces the
# path itself (a swapped symlink is replaced, never followed; a directory
# destination fails). The caller must only invoke this after the truststore has
# been hardened successfully.
install_out_env() {
  local destination="$1"
  local options="$2"
  local dir_path="$3"
  local base="$4"
  local canonical_dir="$5"
  local env_staging
  env_staging="$(mktemp "${dir_path}/${base}.tmp.XXXXXX")" \
    || error "cannot create staging file for out-env: $destination"
  ENV_STAGING_FILE="$env_staging"
  if [[ -f "$dir_path/$base" && ! -L "$dir_path/$base" && -s "$dir_path/$base" ]]; then
    cat -- "$dir_path/$base" > "$env_staging" \
      || error "cannot read existing out-env: $destination"
    local last_byte
    last_byte="$(tail -c 1 -- "$env_staging" | od -An -tx1 | tr -d ' \n')" \
      || error "cannot inspect existing out-env: $destination"
    if [[ "$last_byte" != "0a" ]]; then
      printf '\n' >> "$env_staging"
    fi
  fi
  printf 'JAVA_TOOL_OPTIONS=%s\n' "$options" >> "$env_staging"
  chmod 600 -- "$env_staging"
  dir_fd_resolves_to "$dir_path" "$canonical_dir" \
    || error "refusing to install out-env: parent directory changed during hardening: $canonical_dir"
  if [[ -L "$dir_path/$base" || -d "$dir_path/$base" ]]; then
    error "refusing to replace out-env after hardening: destination became a symlink or directory: $destination"
  fi
  mv -T -f -- "$env_staging" "$dir_path/$base" \
    || error "cannot install out-env at $destination"
  ENV_STAGING_FILE=""
  dir_fd_resolves_to "$dir_path" "$canonical_dir" \
    || error "out-env parent directory changed during install; the env line was written to the original (renamed) directory and the configured path is no longer trustworthy: $destination"
}

run_check_only() {
  require_file "$PINS" "pin file"
  printf 'Checking %s (read-only)...\n' "$source_store" >&2
  if ! "$java_bin" "$TOOL" verify --store "$source_store" --pins "$PINS"; then
    cat >&2 <<'EOF'
ERROR: the selected JDK truststore still contains one or more incident trust anchors.
Update to a JDK release whose cacerts has the eMudhra incident anchors removed,
or follow OpenJDK's guidance:
  https://openjdk.org/emudhra
  https://emudhra.we-have-a-problem.com/
This check did not modify the JDK installation.
EOF
    exit 1
  fi
}

require_effective_store() {
  local mode_name="$1"
  require_file "$PINS" "pin file"
  require_file "$CANARY" "canary certificate"
  [[ -n "${JAVA_TOOL_OPTIONS:-}" ]] \
    || error "--$mode_name requires JAVA_TOOL_OPTIONS with -Djavax.net.ssl.trustStore=..."
  local configured_store
  configured_store="$(extract_truststore_path "$JAVA_TOOL_OPTIONS")"
  [[ -n "$configured_store" ]] \
    || error "JAVA_TOOL_OPTIONS does not set -Djavax.net.ssl.trustStore"
  [[ -f "$configured_store" && -r "$configured_store" ]] \
    || error "configured truststore is missing or unreadable: $configured_store (JSSE would fall back to the JDK default)"
}

run_verify_only() {
  require_effective_store "verify-only"
  "$java_bin" "$TOOL" verify-effective --pins "$PINS" --canary-cert "$CANARY"
}

run_probe_tls() {
  # Fail closed before any network access: the hardened store must be the
  # configured effective JSSE truststore and must pass verify-effective.
  require_effective_store "probe-tls"
  "$java_bin" "$TOOL" verify-effective --pins "$PINS" --canary-cert "$CANARY" >&2
  "$java_bin" "$TOOL" probe-tls
}

run_harden() {
  require_file "$PINS" "pin file"
  require_file "$CANARY" "canary certificate"

  local out_store
  local out_store_was_default=0
  if [[ -n "$out_store_arg" ]]; then
    out_store="$out_store_arg"
  else
    out_store="$work_dir/cacerts"
    out_store_was_default=1
  fi

  local out_abs
  local jdk_abs
  if [[ -L "$out_store" ]]; then
    error "refusing to write to a symlink path: $out_store"
  fi
  out_abs="$(realpath -m -- "$out_store")" || error "cannot resolve out-store path: $out_store"
  jdk_abs="$(realpath -m -- "$jdk_home")" || error "cannot resolve JAVA_HOME: $jdk_home"

  if [[ "$out_abs" == "$jdk_abs" || "$out_abs" == "$jdk_abs/"* ]]; then
    error "refusing to write inside JAVA_HOME ($jdk_abs): $out_abs"
  fi
  refuse_inside_repo "out-store" "$out_abs"
  if [[ -L "$out_abs" ]]; then
    error "refusing to write to a symlink path: $out_abs"
  fi
  if [[ -d "$out_abs" ]]; then
    error "out-store is a directory: $out_abs"
  fi
  if [[ -e "$out_abs" && ! -f "$out_abs" ]]; then
    error "out-store is not a regular file: $out_abs"
  fi
  if [[ -e "$out_abs" ]]; then
    if same_file "$out_abs" "$source_store"; then
      error "out-store is the JDK truststore (same file): $out_abs"
    fi
    refuse_repo_hardlink "out-store" "$out_abs"
  fi

  # Validate the path before creating any file. It is embedded in
  # JAVA_TOOL_OPTIONS, so reject newlines, carriage returns, and double
  # quotes up front instead of after a hardened store has been written.
  if [[ "$out_abs" == *$'\n'* || "$out_abs" == *$'\r'* || "$out_abs" == *'"'* ]]; then
    error "out-store path must not contain newlines, carriage returns, or double quotes"
  fi

  local out_env_abs=""
  local out_env_dir=""
  local out_env_base=""
  local env_dir_path=""
  if [[ "$out_env_set" -eq 1 ]]; then
    if [[ -L "$out_env" ]]; then
      error "refusing to write to a symlink path: $out_env"
    fi
    out_env_abs="$(realpath -m -- "$out_env")" || error "cannot resolve out-env path: $out_env"
    refuse_inside_repo "out-env" "$out_env_abs"
    if [[ "$out_env_abs" == "$jdk_abs" || "$out_env_abs" == "$jdk_abs/"* ]]; then
      error "refusing to write inside JAVA_HOME ($jdk_abs): $out_env_abs"
    fi
    if [[ -L "$out_env_abs" ]]; then
      error "refusing to write to a symlink path: $out_env_abs"
    fi
    if [[ -d "$out_env_abs" ]]; then
      error "out-env is a directory: $out_env_abs"
    fi
    # Reject FIFOs, sockets, and devices before the hardlink scan and before
    # anything opens the path: a FIFO would block forever.
    if [[ -e "$out_env_abs" && ! -f "$out_env_abs" ]]; then
      error "out-env is not a regular file: $out_env_abs"
    fi
    if same_file "$out_env_abs" "$source_store"; then
      error "out-env is the JDK truststore (same file): $out_env_abs"
    fi
    if [[ "$out_env_abs" == "$out_abs" ]]; then
      error "--out-store and --out-env must not be the same path: $out_abs"
    fi
    if same_file "$out_env_abs" "$out_abs"; then
      error "--out-store and --out-env must not be the same file: $out_abs"
    fi
    # The env file is replaced, not created with parents, so a missing parent
    # must fail before the (multi-second) hardening run.
    out_env_dir="$(dirname -- "$out_env_abs")"
    out_env_base="$(basename -- "$out_env_abs")"
    if [[ ! -d "$out_env_dir" ]]; then
      error "out-env parent directory does not exist: $out_env_dir"
    fi
    refuse_repo_hardlink "out-env" "$out_env_abs"
    # Pin the validated parent inode. Staging and installation go through
    # /proc/self/fd, so a rename or symlink swap of any parent component during
    # the multi-second harden cannot redirect the env write.
    [[ -d /proc/self/fd ]] \
      || error "directory-fd pinning requires /proc/self/fd (Linux)"
    exec {ENV_DIR_FD}<"$out_env_dir" \
      || error "cannot open out-env parent directory: $out_env_dir"
    env_dir_path="/proc/self/fd/$ENV_DIR_FD"
    dir_fd_resolves_to "$env_dir_path" "$out_env_dir" \
      || error "out-env parent directory changed before hardening: $out_env_dir"
  fi

  local out_dir
  local out_base
  local out_dir_path
  out_dir="$(dirname -- "$out_abs")"
  out_base="$(basename -- "$out_abs")"
  # Never create the parent here: mkdir would run before the directory fd is
  # pinned and could follow a parent symlink swapped in after validation. The
  # caller must provide an existing directory, matching --out-env.
  if [[ ! -d "$out_dir" ]]; then
    error "out-store parent directory does not exist: $out_dir (create it before hardening)"
  fi
  [[ -d /proc/self/fd ]] \
    || error "directory-fd pinning requires /proc/self/fd (Linux)"
  exec {OUT_DIR_FD}<"$out_dir" \
    || error "cannot open out-store parent directory: $out_dir"
  out_dir_path="/proc/self/fd/$OUT_DIR_FD"
  dir_fd_resolves_to "$out_dir_path" "$out_dir" \
    || error "out-store parent directory changed before hardening: $out_dir"

  # Stage beside the final path through the pinned parent. The destination is
  # not written until the hardening tool succeeds, so a failed run cannot
  # downgrade an existing hardened store to an unhardened vendor copy.
  local staging
  staging="$(mktemp "${out_dir_path}/${out_base}.tmp.XXXXXX")" \
    || error "cannot create staging file for $out_abs"
  STAGING_FILE="$staging"
  cp -- "$source_store" "$staging"
  chmod 600 -- "$staging"

  printf 'Hardening %s from %s (staged at %s)...\n' "$out_abs" "$source_store" "$staging" >&2
  local tool_json
  tool_json="$("$java_bin" "$TOOL" harden \
    --store "$staging" \
    --pins "$PINS" \
    --canary-cert "$CANARY" \
    --canary-alias "$DEFAULT_CANARY_ALIAS" \
    --min-certs "$MIN_CERTS")"
  # GNU mv -T treats the destination as a normal file: a directory (or a
  # symlink to one) swapped in after the checks fails instead of receiving the
  # hardened store. Re-check both the canonical path and the pinned directory so
  # the error is explicit and a parent swap cannot redirect the install.
  if [[ -L "$out_abs" || -d "$out_abs" ]]; then
    error "refusing to replace out-store after hardening: destination became a symlink or directory: $out_abs"
  fi
  dir_fd_resolves_to "$out_dir_path" "$out_dir" \
    || error "refusing to install out-store: parent directory changed during hardening: $out_dir"
  if [[ -L "$out_dir_path/$out_base" || -d "$out_dir_path/$out_base" ]]; then
    error "refusing to replace out-store after hardening: destination became a symlink or directory: $out_abs"
  fi
  mv -T -f -- "$staging" "$out_dir_path/$out_base" \
    || error "cannot install hardened store at $out_abs"
  STAGING_FILE=""
  dir_fd_resolves_to "$out_dir_path" "$out_dir" \
    || error "out-store parent directory changed during install; the hardened store was written to the original (renamed) directory and the configured path is no longer trustworthy: $out_abs"
  out_store="$out_abs"

  local store_type
  store_type="$(detect_store_type "$out_store")"

  local truststore_options
  local existing_options
  local new_tool_options
  truststore_options="-Djavax.net.ssl.trustStore=\"$out_store\" -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=$store_type"
  existing_options="${JAVA_TOOL_OPTIONS:-}"
  if [[ "$existing_options" == *$'\n'* || "$existing_options" == *$'\r'* ]]; then
    error "JAVA_TOOL_OPTIONS must not contain newlines or carriage returns"
  fi
  if [[ -n "$existing_options" ]]; then
    new_tool_options="$existing_options $truststore_options"
  else
    new_tool_options="$truststore_options"
  fi

  printf 'Hardened store (final path): %s (%s)\n' "$out_store" "$store_type" >&2

  # The store is hardened and installed; only now touch the env file. Staging
  # happens before any stdout output so a refused destination cannot leave a
  # half-reported success.
  if [[ -n "$out_env" ]]; then
    install_out_env "$out_env_abs" "$new_tool_options" \
      "$env_dir_path" "$out_env_base" "$out_env_dir"
  fi

  if [[ "$print_env" -eq 1 ]]; then
    # Keep stdout eval-safe: the tool JSON goes to stderr so stdout contains
    # only the shell-quoted export line.
    printf '%s\n' "$tool_json" >&2
  else
    printf '%s\n' "$tool_json"
  fi

  if [[ "$out_env_set" -eq 1 ]]; then
    if [[ "$out_store_was_default" -eq 1 ]]; then
      keep_work_dir=1
    fi
  elif [[ "$print_env" -eq 1 ]]; then
    printf 'export JAVA_TOOL_OPTIONS=%q\n' "$new_tool_options"
    if [[ "$out_store_was_default" -eq 1 ]]; then
      keep_work_dir=1
    fi
  else
    printf 'JAVA_TOOL_OPTIONS=%s\n' "$new_tool_options"
    if [[ "$out_store_was_default" -eq 1 ]]; then
      keep_work_dir=1
    fi
  fi

  if [[ "$out_store_was_default" -eq 1 ]]; then
    printf 'Default out-store directory kept at %s\n' "$work_dir" >&2
  fi
}

case "$mode" in
  harden)
    run_harden
    ;;
  verify-only)
    run_verify_only
    ;;
  check-only)
    run_check_only
    ;;
  probe-tls)
    run_probe_tls
    ;;
  *)
    error "internal error: unknown mode $mode"
    ;;
esac
