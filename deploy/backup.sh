#!/usr/bin/env bash
# Backs up every database and the encryption/signing keys. Keeps 14 days.
# The keys are in the backup on purpose: without them, stored TOTP seeds, bank numbers and broker
# tokens can't be decrypted. Keep this folder private and copy it off the server.
set -euo pipefail
cd "$(dirname "$0")/.."

dest="${1:-$HOME/tradeflow-backups}"
stamp="$(date +%Y%m%d-%H%M%S)"
umask 077
mkdir -p "$dest"

user="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2)"
docker compose exec -T postgres pg_dumpall -U "${user:-tradeflow}" | gzip > "$dest/databases-$stamp.sql.gz"

for volume in user_data_keys live_data_keys jwt_private jwt_public; do
  docker run --rm -v "tradeflow_${volume}:/data:ro" alpine:3 tar -C /data -czf - . > "$dest/${volume}-$stamp.tar.gz"
done

find "$dest" -type f -mtime +14 -delete
echo "Backup written to $dest"
