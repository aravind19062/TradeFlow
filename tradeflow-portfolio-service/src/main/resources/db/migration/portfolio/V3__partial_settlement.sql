-- An order can now settle in several trades and release only its unfilled part. DEMO only.
ALTER TABLE reservations ADD COLUMN remaining_cash NUMERIC(19,4);
ALTER TABLE reservations ADD COLUMN settled_quantity BIGINT NOT NULL DEFAULT 0;
ALTER TABLE reservations ADD COLUMN released_quantity BIGINT NOT NULL DEFAULT 0;

UPDATE reservations SET remaining_cash = CASE WHEN status = 'ACTIVE' THEN cash_amount ELSE 0 END;
UPDATE reservations SET settled_quantity = quantity WHERE status = 'SETTLED';
UPDATE reservations SET released_quantity = quantity WHERE status = 'RELEASED';

ALTER TABLE reservations ALTER COLUMN remaining_cash SET NOT NULL;
