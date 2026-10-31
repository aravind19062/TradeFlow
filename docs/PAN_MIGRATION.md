# PAN: stop collecting, then remove stored values (D7)

## Why

TradeFlow has no use for a user's PAN. Identity verification for real trading is the broker's KYC, done
in the broker's own onboarding. Holding PAN numbers adds regulatory and breach exposure for no benefit.

## Done (phase 2, `feature/dual-mode-trading`)

1. **Collection stopped.** `RegisterRequest` no longer has a `panNumber` field. A client that still sends
   one has it ignored (unknown JSON properties are dropped), and nothing is stored. A test
   (`AuthIntegrationTest.registrationIssuesValidTokenAndPublishesEvent`) proves the column stays `NULL`.
2. **No reads or writes.** The `User` entity no longer maps `users.pan_number`. No API returns it
   (`UserResponse` never did), and no log or event contains it.
3. **Frontend.** The registration form no longer asks for PAN.
4. **Column untouched.** Existing values remain in `users.pan_number`, so nothing is deleted without
   approval.

## Inventory (run against the database; counts only, never values)

```sql
SELECT count(*) AS users_total,
       count(pan_number) AS users_with_pan
FROM users;
```

## Proposed removal (needs your approval; not yet applied)

Step A: back up, then clear the values. This changes no other user data.

```sql
-- Flyway migration V3__purge_pan.sql (user-service), to be added only after approval
UPDATE users SET pan_number = NULL WHERE pan_number IS NOT NULL;
```

Step B: one release later, once no running version maps the column, drop it.

```sql
-- Flyway migration V4__drop_pan_column.sql
ALTER TABLE users DROP COLUMN pan_number;
```

Notes:
* Take a database backup first. If the backup must keep PAN values for a retention obligation, handle
  that backup under the same access controls, and expire it per your policy.
* Step A is irreversible by design. After it, PAN exists nowhere in TradeFlow's live database.
* Order: A then B. Running B first would also delete the values, but in the same step as a schema change,
  which is harder to review and roll back.
