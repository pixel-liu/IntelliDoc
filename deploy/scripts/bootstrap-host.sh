#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run with sudo: sudo $0" >&2
  exit 1
fi

if [[ ! -r /etc/os-release ]]; then
  echo "Cannot identify the operating system. This script supports Ubuntu and Debian." >&2
  exit 1
fi

source /etc/os-release
case "${ID:-}" in
  ubuntu|debian) ;;
  *)
    echo "Unsupported distribution: ${ID:-unknown}. Install the prerequisites manually." >&2
    exit 1
    ;;
esac

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y \
  ca-certificates \
  curl \
  docker.io \
  nginx \
  openjdk-17-jre-headless \
  openssl \
  python3 \
  python3-pip \
  python3-venv \
  unzip

if ! docker compose version >/dev/null 2>&1; then
  if ! apt-get install -y docker-compose-v2; then
    apt-get install -y docker-compose-plugin
  fi
fi

systemctl enable --now docker
systemctl enable --now nginx

echo "Host prerequisites installed."
java -version
docker compose version
nginx -v
