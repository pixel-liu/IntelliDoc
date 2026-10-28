#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
INFRA_ENV="$DEPLOY_DIR/.env.infrastructure"
APP_ENV="$DEPLOY_DIR/app.env"
COMPOSE_FILE="$DEPLOY_DIR/compose.infrastructure.yml"

failed=0
check_command() {
  if command -v "$1" >/dev/null 2>&1; then
    printf '[ok] %s\n' "$1"
  else
    printf '[missing] %s\n' "$1" >&2
    failed=1
  fi
}

for command_name in docker java nginx curl openssl python3; do
  check_command "$command_name"
done

if [[ ! -f "$INFRA_ENV" || ! -f "$APP_ENV" ]]; then
  echo "[missing] Run deploy/scripts/init-env.sh first." >&2
  exit 1
fi

if grep -Eq 'CHANGE_ME|__[A-Z0-9_]+__' "$INFRA_ENV" "$APP_ENV"; then
  echo "[invalid] Environment files still contain placeholders." >&2
  failed=1
else
  echo "[ok] environment placeholders"
fi

if command -v docker >/dev/null 2>&1; then
  if docker compose --env-file "$INFRA_ENV" -f "$COMPOSE_FILE" config --quiet; then
    echo "[ok] Docker Compose configuration"
  else
    echo "[invalid] Docker Compose configuration" >&2
    failed=1
  fi
fi

if [[ -x /opt/intellidoc/liteparse/bin/lit ]]; then
  /opt/intellidoc/liteparse/bin/lit --help >/dev/null
  echo "[ok] LiteParse"
else
  echo "[deferred] LiteParse will be installed by install-release.sh"
fi

if [[ -r /proc/sys/vm/max_map_count ]]; then
  max_map_count="$(cat /proc/sys/vm/max_map_count)"
  if (( max_map_count >= 262144 )); then
    echo "[ok] vm.max_map_count=$max_map_count"
  else
    echo "[invalid] vm.max_map_count=$max_map_count; Elasticsearch needs at least 262144" >&2
    failed=1
  fi
fi

if (( failed != 0 )); then
  exit 1
fi

echo "Preflight checks passed."
