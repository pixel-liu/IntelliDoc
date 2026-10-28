#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with sudo: sudo $0" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RELEASE_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
APP_HOST="${1:-}"
STORAGE_HOST="${2:-}"

if [[ ! "$APP_HOST" =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "Usage: sudo $0 <app-host> [storage-host]" >&2
  exit 1
fi
if [[ -n "$STORAGE_HOST" && ! "$STORAGE_HOST" =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "Invalid storage host: $STORAGE_HOST" >&2
  exit 1
fi

for required_path in \
  "$RELEASE_DIR/backend/intellidoc.jar" \
  "$RELEASE_DIR/frontend/index.html" \
  "$DEPLOY_DIR/.env.infrastructure" \
  "$DEPLOY_DIR/app.env"; do
  [[ -e "$required_path" ]] || { echo "Missing: $required_path" >&2; exit 1; }
done

if grep -Eq 'CHANGE_ME|__[A-Z0-9_]+__' "$DEPLOY_DIR/.env.infrastructure" "$DEPLOY_DIR/app.env"; then
  echo "Environment files still contain placeholders." >&2
  exit 1
fi

command -v docker >/dev/null 2>&1 || { echo "Docker is required." >&2; exit 1; }
docker compose version >/dev/null
command -v java >/dev/null 2>&1 || { echo "Java 17 is required." >&2; exit 1; }
command -v nginx >/dev/null 2>&1 || { echo "Nginx is required." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "Python 3 is required." >&2; exit 1; }

java_major="$(java -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')"
if [[ "$java_major" != "17" ]]; then
  echo "Java 17 is required; found major version $java_major." >&2
  exit 1
fi

if ! id intellidoc >/dev/null 2>&1; then
  useradd --system --home-dir /opt/intellidoc --shell /usr/sbin/nologin intellidoc
fi

mkdir -p /opt/intellidoc/logs /etc/intellidoc /var/www/intellidoc /opt/intellidoc/deploy
install -m 0640 -o intellidoc -g intellidoc "$RELEASE_DIR/backend/intellidoc.jar" /opt/intellidoc/app.jar
cp -a "$RELEASE_DIR/frontend/." /var/www/intellidoc/
cp -a "$DEPLOY_DIR/." /opt/intellidoc/deploy/
install -m 0600 -o root -g intellidoc "$DEPLOY_DIR/app.env" /etc/intellidoc/app.env
chown -R intellidoc:intellidoc /opt/intellidoc/logs
chown -R root:root /var/www/intellidoc

if [[ ! -x /opt/intellidoc/liteparse/bin/lit ]]; then
  python3 -m venv /opt/intellidoc/liteparse
  /opt/intellidoc/liteparse/bin/pip install --disable-pip-version-check liteparse==2.14.4
fi

sysctl -w vm.max_map_count=262144 >/dev/null
printf 'vm.max_map_count=262144\n' >/etc/sysctl.d/99-intellidoc.conf

docker compose \
  --env-file /opt/intellidoc/deploy/.env.infrastructure \
  -f /opt/intellidoc/deploy/compose.infrastructure.yml \
  up -d --build

install -m 0644 "$DEPLOY_DIR/systemd/intellidoc.service" /etc/systemd/system/intellidoc.service
sed "s/__APP_HOST__/$APP_HOST/g" "$DEPLOY_DIR/nginx/intellidoc.conf.template" \
  >/etc/nginx/conf.d/intellidoc.conf

if [[ -n "$STORAGE_HOST" ]]; then
  sed "s/__STORAGE_HOST__/$STORAGE_HOST/g" "$DEPLOY_DIR/nginx/minio.conf.template" \
    >/etc/nginx/conf.d/intellidoc-minio.conf
fi

nginx -t
systemctl daemon-reload
systemctl enable --now nginx
systemctl reload nginx
systemctl enable intellidoc
systemctl restart intellidoc

echo "Installed IntelliDoc."
echo "Check: systemctl status intellidoc --no-pager"
echo "Check: curl -fsS http://127.0.0.1:8081/api/v1/users/login -o /dev/null -w '%{http_code}\n'"
