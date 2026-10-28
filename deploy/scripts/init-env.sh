#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_ORIGIN="${1:-}"
MINIO_PUBLIC_URL="${2:-}"

if [[ ! "$APP_ORIGIN" =~ ^https?://[^[:space:],/]+(:[0-9]+)?$ ]]; then
  echo "Usage: $0 <app-origin> <minio-public-url>" >&2
  echo "Example: $0 https://docs.example.com https://storage.example.com" >&2
  echo "IP-only: $0 http://203.0.113.10 http://203.0.113.10:19000" >&2
  exit 1
fi

if [[ ! "$MINIO_PUBLIC_URL" =~ ^https?://[^[:space:],/]+(:[0-9]+)?$ ]]; then
  echo "Invalid MinIO public URL: $MINIO_PUBLIC_URL" >&2
  exit 1
fi

for command_name in openssl sed; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing required command: $command_name" >&2
    exit 1
  }
done

INFRA_ENV="$DEPLOY_DIR/.env.infrastructure"
APP_ENV="$DEPLOY_DIR/app.env"
if [[ -e "$INFRA_ENV" || -e "$APP_ENV" ]]; then
  echo "Refusing to overwrite existing environment files:" >&2
  [[ -e "$INFRA_ENV" ]] && echo "  $INFRA_ENV" >&2
  [[ -e "$APP_ENV" ]] && echo "  $APP_ENV" >&2
  exit 1
fi

umask 077
cp "$DEPLOY_DIR/env.infrastructure.example" "$INFRA_ENV"
cp "$DEPLOY_DIR/app.env.example" "$APP_ENV"

mysql_password="$(openssl rand -hex 24)"
mysql_root_password="$(openssl rand -hex 24)"
redis_password="$(openssl rand -hex 24)"
minio_password="$(openssl rand -hex 24)"
elastic_password="$(openssl rand -hex 24)"
jwt_secret="$(openssl rand -base64 32 | tr -d '\r\n')"
admin_password="$(openssl rand -hex 16)"

escape_sed() {
  printf '%s' "$1" | sed 's/[#&\]/\&/g'
}

replace_token() {
  local file_path="$1"
  local token="$2"
  local value="$3"
  sed -i "s#${token}#$(escape_sed "$value")#g" "$file_path"
}

replace_token "$INFRA_ENV" __MYSQL_PASSWORD__ "$mysql_password"
replace_token "$INFRA_ENV" __MYSQL_ROOT_PASSWORD__ "$mysql_root_password"
replace_token "$INFRA_ENV" __REDIS_PASSWORD__ "$redis_password"
replace_token "$INFRA_ENV" __MINIO_ROOT_PASSWORD__ "$minio_password"
replace_token "$INFRA_ENV" __ELASTIC_PASSWORD__ "$elastic_password"

replace_token "$APP_ENV" __MYSQL_PASSWORD__ "$mysql_password"
replace_token "$APP_ENV" __REDIS_PASSWORD__ "$redis_password"
replace_token "$APP_ENV" __MINIO_ROOT_PASSWORD__ "$minio_password"
replace_token "$APP_ENV" __ELASTIC_PASSWORD__ "$elastic_password"
replace_token "$APP_ENV" __JWT_SECRET_KEY__ "$jwt_secret"
replace_token "$APP_ENV" __ADMIN_PASSWORD__ "$admin_password"
replace_token "$APP_ENV" __APP_ORIGIN__ "$APP_ORIGIN"
replace_token "$APP_ENV" __MINIO_PUBLIC_URL__ "$MINIO_PUBLIC_URL"

chmod 600 "$INFRA_ENV" "$APP_ENV"

echo "Created:"
echo "  $INFRA_ENV"
echo "  $APP_ENV"
echo
echo "Next: edit $APP_ENV and replace both CHANGE_ME API keys."
echo "The generated one-time admin password is stored in ADMIN_BOOTSTRAP_PASSWORD."
