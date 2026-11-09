-- A user's bank account, shown on the profile page. One per user. The full account number is
-- encrypted (AES-256-GCM, bound to the user id) and never returned by the API; only the last four
-- digits are readable. Additive only.
CREATE TABLE user_bank_accounts (
    user_id                    BIGINT       PRIMARY KEY REFERENCES users (id),
    account_holder_name        VARCHAR(100) NOT NULL,
    bank_name                  VARCHAR(100) NOT NULL,
    account_number_ciphertext  VARCHAR(512) NOT NULL,
    account_last4              VARCHAR(4)   NOT NULL,
    ifsc                       VARCHAR(11)  NOT NULL,
    account_type               VARCHAR(16)  NOT NULL,
    created_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    version                    BIGINT       NOT NULL DEFAULT 0
);
