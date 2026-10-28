#!/usr/bin/env bash
set -euo pipefail

APP_URL="${1:-http://127.0.0.1}"
DEPLOY_DIR="/opt/intellidoc/deploy"

echo "Infrastructure:"
docker compose \
  --env-file "$DEPLOY_DIR/.env.infrastructure" \
  -f "$DEPLOY_DIR/compose.infrastructure.yml" \
  ps

echo
echo "Backend service:"
systemctl is-active intellidoc

echo
echo "Nginx health:"
curl -fsS "$APP_URL/deployment-health"

echo
echo "Backend authentication endpoint (400/401/405 is acceptable without credentials):"
http_code="$(curl -sS -o /dev/null -w '%{http_code}' "$APP_URL/api/v1/users/login")"
case "$http_code" in
  400|401|405) echo "HTTP $http_code" ;;
  *) echo "Unexpected HTTP $http_code" >&2; exit 1 ;;
esac

echo "Basic deployment checks passed. Browser upload/chat verification is still required."
