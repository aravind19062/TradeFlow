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

## Audit (1 Nov 2026, running Docker stack)

| Question | Finding |
|---|---|
| Where is PAN collected? | Nowhere any more. `RegisterRequest` has no PAN field; the registration form has no PAN input. The API ignores a `panNumber` sent by an old client (tested). |
| Where is it stored? | Only `tradeflow_user.users.pan_number`. No other column in any of the six databases is PAN-like. |
| How much? | 100 accounts, **1** with a value (id 7, created 27 Oct, before collection stopped; the value has a valid PAN format). Values were not displayed during the audit. |
| Does any service need it? | No. The `User` entity does not map the column; no service reads it. |
| Exposed in API responses? | No. `UserResponse` has never had a PAN field (checked across git history). |
| In events / Kafka? | No. `UserRegistered` has carried only userId, email and name since events were introduced (`b98c6d1`); no outbox row mentions PAN. |
| In logs? | No. No code logs registration payloads, and the current containers' logs predate nothing that accepted PAN (they were recreated after collection stopped). |
| Required by a broker's KYC? | Not by TradeFlow: Indian brokers run KYC (PAN, Aadhaar, in-person verification) in **their own** onboarding, and TradeFlow links an existing broker account via the broker's OAuth login. Whether a specific broker's API ever needs PAN from a partner can only be confirmed once you choose a broker; none of the common retail broker APIs (order placement, holdings, funds) take a PAN parameter. If one did, it would be fetched from or sent to the broker at that moment, not stored by TradeFlow. |
| Backups / dumps? | None made by TradeFlow. The `postgres_data` Docker volume holds the one value until it is cleared; PostgreSQL may keep the old row version in dead tuples until `VACUUM`. |

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

Consequences:
* Only account id 7 changes, and only that one column. Login, password, MFA, orders, portfolio,
  readiness and every other piece of that account are untouched.
* Nothing in TradeFlow stops working: no code reads the column.
* The value cannot be recovered from TradeFlow afterwards. If you want to keep it, copy it somewhere you
  control *before* step A.
* After step A, run `VACUUM (FULL) users;` (a few ms on 100 rows; locks the table briefly) so the old row
  version is physically removed from the data files, not just marked dead.
* Step B is a schema change. Every running user-service version already ignores the column, so it can
  be dropped in the same release; I proposed two steps only so each is reviewable on its own.

Notes:
* Take a database backup first. If the backup must keep PAN values for a retention obligation, handle
  that backup under the same access controls, and expire it per your policy.
* Step A is irreversible by design. After it, PAN exists nowhere in TradeFlow's live database.
* Order: A then B. Running B first would also delete the values, but in the same step as a schema change,
  which is harder to review and roll back.
