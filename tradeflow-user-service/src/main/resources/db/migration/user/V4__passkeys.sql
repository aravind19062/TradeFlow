-- Passkeys (WebAuthn). Only public keys are stored; the private key never leaves the user's device.
-- Challenges are single-use and short-lived. user_id is null for sign-in, where the user is unknown
-- until the passkey is presented. Additive only.
CREATE TABLE user_passkeys (
    id               UUID          PRIMARY KEY,
    user_id          BIGINT        NOT NULL REFERENCES users (id),
    credential_id    VARCHAR(1024) NOT NULL UNIQUE,
    attested_data    VARCHAR(4096) NOT NULL,
    sign_count       BIGINT        NOT NULL,
    backup_eligible  BOOLEAN       NOT NULL,
    backup_state     BOOLEAN       NOT NULL,
    name             VARCHAR(60)   NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at     TIMESTAMP WITH TIME ZONE,
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX idx_user_passkeys_user ON user_passkeys (user_id);

CREATE TABLE passkey_challenges (
    id          UUID        PRIMARY KEY,
    user_id     BIGINT      REFERENCES users (id),
    purpose     VARCHAR(16) NOT NULL,
    challenge   VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at     TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_passkey_challenges_expiry ON passkey_challenges (expires_at);
