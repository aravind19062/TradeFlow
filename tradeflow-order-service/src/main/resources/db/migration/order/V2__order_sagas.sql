-- Explicit saga state per order: where the order is in the cross-service workflow,
-- when the current step times out, and how often a step was re-driven.
CREATE TABLE order_sagas (
    order_id      UUID         PRIMARY KEY REFERENCES orders (id),
    state         VARCHAR(30)  NOT NULL,
    deadline_at   TIMESTAMP WITH TIME ZONE,
    redrive_count INTEGER      NOT NULL DEFAULT 0,
    last_error    VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_order_sagas_state_deadline ON order_sagas (state, deadline_at);

-- Backfill orders that existed before sagas were tracked. Terminal orders are
-- recorded as finished; in-flight ones get a deadline of "now" so the recovery
-- job re-evaluates them on its first run.
INSERT INTO order_sagas (order_id, state, deadline_at, redrive_count, created_at, updated_at, version)
SELECT id,
       CASE status
           WHEN 'FILLED' THEN 'COMPLETED'
           WHEN 'REJECTED' THEN 'REJECTED'
           WHEN 'CANCELLED' THEN 'RELEASED'
           WHEN 'PENDING' THEN 'AWAITING_RESERVATION'
           ELSE 'RESTING'
       END,
       CASE WHEN status IN ('PENDING', 'OPEN') THEN CURRENT_TIMESTAMP ELSE NULL END,
       0, created_at, CURRENT_TIMESTAMP, 0
FROM orders;
