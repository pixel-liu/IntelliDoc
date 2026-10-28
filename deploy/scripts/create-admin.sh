#!/usr/bin/env bash
set -euo pipefail

APP_DIR="/opt/intellidoc"
APP_ENV="/etc/intellidoc/app.env"

if [[ ! -f "$APP_DIR/app.jar" || ! -f "$APP_ENV" ]]; then
  echo "IntelliDoc is not installed under /opt/intellidoc and /etc/intellidoc." >&2
  exit 1
fi

if systemctl is-active --quiet intellidoc; then
  echo "Stop the running service before one-time administrator creation:" >&2
  echo "  sudo systemctl stop intellidoc" >&2
  exit 1
fi

cd "$APP_DIR"
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" == \#* ]] && continue
  key="${line%%=*}"
  value="${line#*=}"
  if [[ ! "$key" =~ ^[A-Z_][A-Z0-9_]*$ || "$line" != *=* ]]; then
    echo "Invalid environment entry in $APP_ENV: $key" >&2
    exit 1
  fi
  export "$key=$value"
done <"$APP_ENV"

bootstrap_log="$(mktemp /tmp/intellidoc-admin-bootstrap.XXXXXX.log)"
bootstrap_pid=""
cleanup() {
  if [[ -n "$bootstrap_pid" ]] && kill -0 "$bootstrap_pid" >/dev/null 2>&1; then
    kill -INT "$bootstrap_pid" >/dev/null 2>&1 || true
    wait "$bootstrap_pid" >/dev/null 2>&1 || true
  fi
  rm -f "$bootstrap_log"
}
trap cleanup EXIT INT TERM

/usr/bin/java -jar "$APP_DIR/app.jar" \
  --admin.bootstrap.enabled=true \
  --knowledge.bootstrap.enabled=false \
  > >(tee "$bootstrap_log") 2>&1 &
bootstrap_pid="$!"

for _ in $(seq 1 60); do
  if grep -Eq '管理员账号.*(创建成功|已存在)' "$bootstrap_log"; then
    echo "Administrator bootstrap completed."
    exit 0
  fi
  if ! kill -0 "$bootstrap_pid" >/dev/null 2>&1; then
    wait "$bootstrap_pid"
    echo "Administrator bootstrap process exited before success was confirmed." >&2
    exit 1
  fi
  sleep 2
done

echo "Timed out waiting for administrator bootstrap." >&2
exit 1
