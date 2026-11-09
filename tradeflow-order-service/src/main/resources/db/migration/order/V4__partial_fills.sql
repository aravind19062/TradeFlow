-- Simulated matching engine: partial fills and limited liquidity per tick. DEMO only. Additive only.
-- orders.status also takes PARTIALLY_FILLED (16 characters; the column is VARCHAR(20)).

-- The touch shown when a MARKET order was accepted; anchors the book until the first tick arrives.
ALTER TABLE orders ADD COLUMN quoted_price NUMERIC(19,4);

-- An order can now have several trades, and its saga needs to know which ones portfolio has booked.
ALTER TABLE trades ADD COLUMN settled_at TIMESTAMP WITH TIME ZONE;
UPDATE trades SET settled_at = executed_at
 WHERE order_id IN (SELECT order_id FROM order_sagas WHERE state = 'COMPLETED');

-- Shares already taken from the simulated book on each side during the current tick.
ALTER TABLE latest_prices ADD COLUMN ask_consumed BIGINT NOT NULL DEFAULT 0;
ALTER TABLE latest_prices ADD COLUMN bid_consumed BIGINT NOT NULL DEFAULT 0;
