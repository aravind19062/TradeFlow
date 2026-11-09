#!/usr/bin/env bash
# One-time server setup on Ubuntu 24.04 (arm64): Docker, Tailscale, firewall. Run with sudo.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run this with sudo." >&2
  exit 1
fi

apt-get update
apt-get install -y ca-certificates curl git ufw

if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
target_user="${SUDO_USER:-ubuntu}"
usermod -aG docker "$target_user"

if ! command -v tailscale >/dev/null; then
  curl -fsSL https://tailscale.com/install.sh | sh
fi

# Only Tailscale traffic gets in. SSH stays open until Tailscale is confirmed working; closing it
# is the last step in docs/DEPLOY_ORACLE.md, so a mistake here cannot lock you out.
ufw default deny incoming
ufw default allow outgoing
ufw allow in on tailscale0
ufw allow 22/tcp
ufw --force enable

echo
echo "Done. Next: sudo tailscale up --ssh, then log out and back in so the docker group applies."
