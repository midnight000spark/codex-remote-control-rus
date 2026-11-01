#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
DATA_DIR="${CODEX_REMOTE_DATA_DIR:-$CONFIG_HOME/codex_remote_control}"
HOST="${CODEX_REMOTE_HOST:-0.0.0.0}"
PORT="${CODEX_REMOTE_PORT:-8787}"
BACKEND="${CODEX_REMOTE_BACKEND:-codex}"
TOKEN_FILE="${CODEX_REMOTE_TOKEN_FILE:-$DATA_DIR/bridge-token.txt}"
BRIDGE_ID_FILE="${CODEX_REMOTE_BRIDGE_ID_FILE:-$DATA_DIR/bridge-id.txt}"
STATE_FILE="${CODEX_REMOTE_STATE_FILE:-$DATA_DIR/bridge-state.json}"
CLEAR_STATE_ON_START="${CODEX_REMOTE_CLEAR_STATE_ON_START:-0}"
ALLOW_PUBLIC="${CODEX_REMOTE_ALLOW_PUBLIC:-0}"
SYNC_LOG="${CODEX_REMOTE_SYNC_LOG:-0}"
SYNC_LOG_FILE="${CODEX_REMOTE_SYNC_LOG_FILE:-$DATA_DIR/bridge-sync.log}"

migrate_legacy_file() {
  local source_file="$1"
  local target_file="$2"
  if [[ "$source_file" == "$target_file" ]]; then
    return
  fi
  if [[ ! -f "$source_file" || -e "$target_file" ]]; then
    return
  fi
  mkdir -p "$(dirname "$target_file")"
  mv "$source_file" "$target_file"
  echo "Migrated legacy runtime file: $source_file -> $target_file"
}

migrate_legacy_runtime_files() {
  migrate_legacy_file "$ROOT_DIR/data/bridge-token.txt" "$TOKEN_FILE"
  migrate_legacy_file "$ROOT_DIR/data/bridge-id.txt" "$BRIDGE_ID_FILE"
  migrate_legacy_file "$ROOT_DIR/data/bridge-state.json" "$STATE_FILE"
  migrate_legacy_file "$ROOT_DIR/data/bridge-sync.log" "$SYNC_LOG_FILE"
}

ensure_token() {
  node -e '
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const file = process.argv[1];
fs.mkdirSync(path.dirname(file), { recursive: true });
let token = "";
try { token = fs.readFileSync(file, "utf8").trim(); } catch {}
if (!token) {
  token = crypto.randomBytes(24).toString("hex");
  fs.writeFileSync(file, `${token}\n`, { encoding: "utf8", mode: 0o600 });
  try { fs.chmodSync(file, 0o600); } catch {}
}
process.stdout.write(token);
' "$TOKEN_FILE"
}

list_reachable_ips() {
  if command -v hostname >/dev/null 2>&1; then
    hostname -I 2>/dev/null | tr ' ' '\n' || true
  fi
  if command -v ip >/dev/null 2>&1; then
    ip -4 addr show scope global 2>/dev/null | awk '/inet / { sub(/\/.*/, "", $2); print $2 }' || true
  fi
  if command -v ifconfig >/dev/null 2>&1; then
    ifconfig 2>/dev/null | awk '/inet / { print $2 }' || true
  fi
}

is_loopback_host() {
  case "$1" in
    127.*|localhost|::1|"[::1]") return 0 ;;
    *) return 1 ;;
  esac
}

ipv4_addresses() {
  list_reachable_ips \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' \
    | grep -vE '^(127\.|0\.0\.0\.0)' \
    | sort -u || true
}

tailscale_addresses() {
  ipv4_addresses \
    | grep -E '^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.' || true
}

check_exposure() {
  if is_loopback_host "$HOST"; then
    return
  fi
  if [[ -n "$(tailscale_addresses)" ]]; then
    return
  fi
  if [[ "$ALLOW_PUBLIC" == "1" || "$ALLOW_PUBLIC" == "true" || "$ALLOW_PUBLIC" == "yes" ]]; then
    echo "ВНИМАНИЕ: Tailscale-интерфейс не найден, мост слушает $HOST." >&2
    echo "           Публичный доступ разрешён принудительно (CODEX_REMOTE_ALLOW_PUBLIC=1)." >&2
    return
  fi
  echo "Не найден адрес Tailscale (100.64.0.0/10), а мост слушает $HOST." >&2
  echo "Так мост будет доступен всем, кто видит этот сетевой интерфейс." >&2
  echo "Варианты:" >&2
  echo "  1. Поднять Tailscale (tailscale up) и запустить снова." >&2
  echo "  2. Слушать только локально: CODEX_REMOTE_HOST=127.0.0.1 npm run bridge" >&2
  echo "  3. Разрешить явно: CODEX_REMOTE_ALLOW_PUBLIC=1 npm run bridge" >&2
  exit 1
}

check_port_available() {
  if ! command -v lsof >/dev/null 2>&1; then
    return
  fi

  local listeners
  listeners="$(lsof -nP "-iTCP:$PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -z "$listeners" ]]; then
    return
  fi

  echo "Port $PORT is already in use; bridge cannot start." >&2
  echo "$listeners" >&2
  echo "Stop the existing bridge or set CODEX_REMOTE_PORT to another port." >&2
  exit 1
}

migrate_legacy_runtime_files
TOKEN="$(ensure_token)"

check_exposure

echo "Мост Codex Remote Control"
echo "Бэкенд:   $BACKEND"
echo "Слушает:  $HOST:$PORT"
echo "Токен:    $TOKEN"
echo
echo "Вставьте любой из этих ws:// адресов в поле Host / URL на телефоне:"
ADDRESSES="$(ipv4_addresses)"
if [[ -n "$ADDRESSES" ]]; then
  echo "$ADDRESSES" | while read -r ip; do
    echo "  ws://$ip:$PORT/?token=$TOKEN"
  done
else
  echo "  (внешние IPv4-адреса не найдены)"
fi
echo
echo "Если Codex требует прокси, включите его в текущем shell перед запуском моста."
echo

cd "$ROOT_DIR"
ARGS=(
  --backend "$BACKEND" \
  --listen "$HOST:$PORT" \
  --state "$STATE_FILE" \
  --token-file "$TOKEN_FILE" \
  --bridge-id-file "$BRIDGE_ID_FILE"
)
if [[ "$SYNC_LOG" == "1" || "$SYNC_LOG" == "true" || "$SYNC_LOG" == "yes" ]]; then
  ARGS+=(--sync-log "$SYNC_LOG_FILE")
  echo "Sync log: $SYNC_LOG_FILE"
fi
if [[ "$CLEAR_STATE_ON_START" == "1" || "$CLEAR_STATE_ON_START" == "true" || "$CLEAR_STATE_ON_START" == "yes" ]]; then
  ARGS+=(--clear-state-on-start)
  echo "State:  clear on startup"
fi

check_port_available
exec npm start -- "${ARGS[@]}" "$@"
