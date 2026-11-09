#!/usr/bin/env bash
# Builds and starts the whole stack with monitoring, then publishes it over Tailscale HTTPS.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "No .env yet. Run deploy/generate-env.sh first." >&2
  exit 1
fi

docker compose -f docker-compose.yml -f docker-compose.oracle.yml \
  --profile apps --profile observability up -d --build

sudo tailscale serve --bg 3000
sudo tailscale serve --bg --https=8443 3001
tailscale serve status
