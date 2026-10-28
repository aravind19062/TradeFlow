-- A release request that arrived before the order's reservation leaves a tombstone,
-- so a late orders.placed is refused instead of locking funds for a dead order.
CREATE TABLE order_tombstones (
    order_id   UUID         PRIMARY KEY,
    reason     VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
