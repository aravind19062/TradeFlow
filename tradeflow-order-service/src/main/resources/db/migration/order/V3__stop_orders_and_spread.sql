-- D2 (Academy exercises): simulated bid/ask and SELL stop / stop-limit orders. Additive only.
-- order_type now also takes STOP and STOP_LIMIT (column is VARCHAR(10); 'STOP_LIMIT' fits).
ALTER TABLE orders ADD COLUMN trigger_price NUMERIC(19,4);
ALTER TABLE orders ADD COLUMN triggered_at TIMESTAMP WITH TIME ZONE;

-- Latest simulated touch per symbol; NULL for ticks recorded before the spread existed (bid = ask = price).
ALTER TABLE latest_prices ADD COLUMN bid NUMERIC(19,4);
ALTER TABLE latest_prices ADD COLUMN ask NUMERIC(19,4);
