#!/usr/bin/env bash
# Health check for the local bridge, its user service, and the Tailscale path.
# Read-only: it never restarts anything and never prints the token.
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
DATA_DIR="${CODEX_REMOTE_DATA_DIR:-$CONFIG_HOME/codex_remote_control}"
TOKEN_FILE="${CODEX_REMOTE_TOKEN_FILE:-$DATA_DIR/bridge-token.txt}"
STATE_FILE="${CODEX_REMOTE_STATE_FILE:-$DATA_DIR/bridge-state.json}"
SERVICE="${CODEX_REMOTE_SERVICE:-codex-remote-bridge.service}"
PORT="${CODEX_REMOTE_PORT:-8787}"
VERSION="$(node -p "require('$ROOT_DIR/package.json').version" 2>/dev/null || echo unknown)"
APK="$ROOT_DIR/android/app/build/outputs/apk/debug/codex-remote-control-v${VERSION}-debug.apk"

FAIL=0
WARN=0

ok() { printf '  \033[32mok\033[0m   %s\n' "$*"; }
warn() { printf '  \033[33mwarn\033[0m %s\n' "$*"; WARN=$((WARN + 1)); }
fail() { printf '  \033[31mfail\033[0m %s\n' "$*"; FAIL=$((FAIL + 1)); }

echo "Codex Remote Control — проверка окружения"
echo

echo "Node"
if [[ "$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)" -ge 20 ]]; then
  ok "node $(node -v)"
else
  fail "нужен Node.js 20+, найден $(node -v 2>/dev/null || echo none)"
fi
ok "версия проекта $VERSION"

echo
echo "Файлы состояния"
if [[ -f "$TOKEN_FILE" ]]; then
  mode="$(stat -c '%a' "$TOKEN_FILE" 2>/dev/null || echo '?')"
  if [[ "$mode" == "600" ]]; then
    ok "токен: $TOKEN_FILE (0600)"
  else
    warn "токен: $TOKEN_FILE имеет права $mode вместо 0600"
  fi
else
  fail "нет файла токена: $TOKEN_FILE"
fi
if [[ -f "$STATE_FILE" ]]; then
  ok "состояние: $STATE_FILE ($(du -h "$STATE_FILE" | cut -f1))"
else
  warn "состояние ещё не создано: $STATE_FILE"
fi

echo
echo "Служба"
if command -v systemctl >/dev/null 2>&1; then
  if systemctl --user is-active --quiet "$SERVICE"; then
    ok "$SERVICE активна"
    since="$(systemctl --user show "$SERVICE" -p ActiveEnterTimestamp --value)"
    [[ -n "$since" ]] && ok "запущена с: $since"
  else
    fail "$SERVICE не активна"
  fi
  linger="$(loginctl show-user "$USER" -p Linger --value 2>/dev/null || echo '?')"
  if [[ "$linger" == "yes" ]]; then
    ok "linger включён (служба переживает выход из сессии)"
  else
    warn "linger=$linger: служба остановится при выходе — loginctl enable-linger $USER"
  fi
else
  warn "systemctl недоступен: служба не проверена"
fi

echo
echo "Туннель (Tailscale)"
if ! command -v tailscale >/dev/null 2>&1; then
  warn "tailscale не установлен"
elif [[ "$(tailscale status --json 2>/dev/null | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.stdout.write(JSON.parse(s).BackendState||"?")}catch{process.stdout.write("?")}})' )" == "Running" ]]; then
  ok "Tailscale запущен"
  self_ip="$(tailscale ip -4 2>/dev/null | head -1)"
  [[ -n "$self_ip" ]] && ok "адрес в tailnet: $self_ip"
else
  fail "Tailscale не в состоянии Running"
fi

echo
echo "Мост"
listen="$(ss -ltn 2>/dev/null | awk -v p=":$PORT" '$4 ~ p "$" {print $4}' | head -1)"
if [[ -n "$listen" ]]; then
  ok "слушает $listen"
  health="$(curl -fsS --max-time 3 "http://${listen%:*}:$PORT/healthz" 2>/dev/null || true)"
  if [[ -n "$health" ]]; then
    health_version="$(printf '%s' "$health" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const j=JSON.parse(s);process.stdout.write(j.version||"?")}catch{process.stdout.write("?")}})')"
    if [[ "$health_version" == "$VERSION" ]]; then
      ok "/healthz отвечает, версия $health_version"
    else
      warn "/healthz отвечает, но версия $health_version вместо $VERSION (служба запущена из старого кода?)"
    fi
  else
    fail "/healthz не отвечает на $listen"
  fi
else
  fail "порт $PORT никто не слушает"
fi

echo
echo "Android"
if [[ -f "$APK" ]]; then
  ok "$(basename "$APK") ($(du -h "$APK" | cut -f1), $(date -r "$APK" '+%d.%m %H:%M'))"
else
  warn "debug APK не собран: npm run apk"
fi

echo
if [[ "$FAIL" -eq 0 ]]; then
  if [[ "$WARN" -eq 0 ]]; then
    echo "Итог: всё на месте."
  else
    echo "Итог: всё на месте, предупреждений: $WARN."
  fi
  exit 0
fi
echo "Итог: ошибок: $FAIL, предупреждений: $WARN."
exit 1