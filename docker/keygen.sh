#!/bin/sh
# Generates TradeFlow's per-installation key material ONCE and never overwrites it.
#   usage: keygen.sh <jwt-private-dir> <jwt-public-dir> <user-data-keys-dir> [owner-uid]
# * RSA-3072 token signing key (PKCS#8): only user-service may read it.
# * Its public key as <kid>.pem, kid = first 16 hex chars of SHA-256(DER public key):
#   every service and the gateway verify tokens with it (see KeyIds.java).
# * AES-256 data-encryption key ring for secrets at rest in user-service (TOTP seeds).
# Docker runs this in the one-shot `keygen` service; `make keys` runs it for non-Docker dev.
set -eu
umask 077
PRIVATE_DIR=$1
PUBLIC_DIR=$2
DATA_DIR=$3
OWNER=${4:-}
mkdir -p "$PRIVATE_DIR" "$PUBLIC_DIR" "$DATA_DIR"

if [ ! -s "$PRIVATE_DIR/signing-key.pem" ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$PRIVATE_DIR/signing-key.pem" 2>/dev/null
  echo "keygen: generated a new token signing key"
fi
kid=$(openssl pkey -in "$PRIVATE_DIR/signing-key.pem" -pubout -outform DER | openssl dgst -sha256 -r | cut -c1-16)
if [ ! -s "$PUBLIC_DIR/$kid.pem" ]; then
  openssl pkey -in "$PRIVATE_DIR/signing-key.pem" -pubout -out "$PUBLIC_DIR/$kid.pem"
fi
echo "keygen: signing key id $kid"

if [ ! -s "$DATA_DIR/data-keys.properties" ]; then
  id="k$(date -u +%Y%m%d%H%M%S)"
  printf '# AES-256 keys for secrets at rest. Add a new id and switch "active" to rotate.\nactive=%s\n%s=%s\n' \
    "$id" "$id" "$(openssl rand -base64 32)" > "$DATA_DIR/data-keys.properties"
  echo "keygen: generated data-encryption key $id"
fi

chmod 700 "$PRIVATE_DIR" "$DATA_DIR"
chmod 600 "$PRIVATE_DIR"/* "$DATA_DIR"/*
chmod 755 "$PUBLIC_DIR"
chmod 644 "$PUBLIC_DIR"/*.pem
if [ -n "$OWNER" ]; then
  chown -R "$OWNER" "$PRIVATE_DIR" "$DATA_DIR"
fi
