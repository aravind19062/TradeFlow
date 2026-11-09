#!/usr/bin/env bash
# Creates .env for the server with private random secrets. Refuses to replace an existing one.
# Usage: deploy/generate-env.sh your-machine.your-tailnet.ts.net
set -euo pipefail

host="${1:-}"
if [ -z "$host" ]; then
  echo "Usage: $0 <tailscale-hostname>" >&2
  exit 1
fi
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo ".env already exists; not touching it." >&2
  exit 1
fi

secret() { openssl rand -hex 24; }

umask 077
cat > .env <<EOF
POSTGRES_USER=tradeflow
POSTGRES_PASSWORD=$(secret)
TRADEFLOW_SERVICE_SECRET_PORTFOLIO=$(secret)
TRADEFLOW_SERVICE_SECRET_LIVE=$(secret)
TRADEFLOW_DEMO_ENABLED=true
TRADEFLOW_DEMO_PASSWORD=$(secret)
TRADEFLOW_MARKET_TICK_MS=30000
TRADEFLOW_AI_PROVIDER=none
ANTHROPIC_API_KEY=
GRAFANA_ADMIN_PASSWORD=$(secret)
TRADEFLOW_PASSKEY_RP_ID=${host}
TRADEFLOW_PASSKEY_ORIGINS=https://${host}
TRADEFLOW_LIVE_BROKER=none
TRADEFLOW_FYERS_APP_ID=
TRADEFLOW_FYERS_SECRET_KEY=
TRADEFLOW_FYERS_REDIRECT_URI=https://${host}/live/broker/callback
EOF
echo "Wrote .env (private to this user). The seeded demo and admin password is TRADEFLOW_DEMO_PASSWORD in that file."
