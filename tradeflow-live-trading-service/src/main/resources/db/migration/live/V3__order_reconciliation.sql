-- Reconciliation of live orders against the broker's own order book (no broker integrated yet).
-- broker_updated_at: the broker's timestamp of the last state we applied, so a delayed or
--   duplicated snapshot can never move an order backwards.
-- review_reason: why an order needs the user's attention (NOT_FOUND_AT_BROKER, DUPLICATE_AT_BROKER,
--   BROKER_ID_MISMATCH). While an order is SUBMITTING, UNKNOWN or NEEDS_REVIEW, no new live order
--   can be placed by that user, so an uncertain order can never be followed by a duplicate.
ALTER TABLE live_orders ADD COLUMN broker_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE live_orders ADD COLUMN review_reason VARCHAR(30);
ALTER TABLE live_orders ADD COLUMN reconcile_checks INT NOT NULL DEFAULT 0;
ALTER TABLE live_orders ADD COLUMN last_reconciled_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_live_orders_status ON live_orders (status, user_id);
