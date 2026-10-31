# Test-account cleanup: proposal (nothing deleted)

Inventory of the running Docker stack on 1 Nov 2026. Read-only queries; no rows were changed.

## How accounts were classified

TradeFlow has no "test" flag on users, so the classification uses the email address. Every automated
creator uses a fixed pattern with a timestamp or random suffix:

| Pattern | Created by | Accounts |
|---|---|---|
| `e2e-<n>@tradeflow.dev` | Playwright `journey.spec.ts` | 18 |
| `ready-<n>@`, `academy-<n>@`, `stop-<n>@`, `limits-<n>@`, `live-<n>@`, `mfa-<n>@tradeflow.dev` | Playwright readiness / academy / live / MFA specs | 64 |
| `live-smoke-<n>@test.dev`, `lat*-<n>@test.dev`, `lb-<n>@test.dev`, `verify<n>@test.dev`, `dbg*<n>@…`, `probe-<n>@`, `csp-<n>@tradeflow.dev` | manual verification scripts during development | 14 |
| **Total test accounts** | | **96** |

Kept, never part of a cleanup:

| Account | Why |
|---|---|
| id 1 `demo@tradeflow.dev`, id 2 `admin@tradeflow.dev`, id 37 `reviewer@tradeflow.dev` | seeded demo accounts (the browser tests log in with them) |
| id 7 `user@aravind.com` | **not a test pattern**: treated as a genuine account (yours). Also the only account holding a PAN; see PAN_MIGRATION.md |

Any account that does not match one of the patterns above is treated as genuine.

## Data attached to the 96 test accounts

| Database | Rows (test accounts) |
|---|---|
| tradeflow_user | refresh_tokens 326, recovery_codes 190, security_events 125, user_totp 20, mfa_challenges 19 |
| tradeflow_order | orders 65, trades 41 |
| tradeflow_portfolio | accounts 96, ledger_entries 137, reservations 63, holdings 41 |
| tradeflow_market_data | price_alerts 15, watchlist_items 13 |
| tradeflow_learning | observed_orders 61, readiness_events 48, readiness_status 32, lesson/exercise progress 16 each, quiz_attempts 8, sizing scenarios 7 |
| tradeflow_live | live_audit_log 8, risk_limits 7 (no broker connections or live orders: no broker exists) |

Plus rows not keyed by user id (outbox and processed-event bookkeeping, order sagas), which the script
would find through the order ids above. Kafka topics also contain these users' past events; they expire
under the normal retention (7 days) and need no action.

## Options

**A. Leave them (recommended for now).** They cost a few hundred KB, and they are what a busy demo
database looks like. The test suites create fresh accounts each run, so they keep accumulating slowly.

**B. Deactivate.** `UPDATE users SET active = false, token_version = token_version + 1 WHERE <pattern>`:
signs them out everywhere and blocks login; nothing is deleted; reversible.

**C. Delete, per service, in one maintenance window.** A script per database, generated from the id list
above and reviewed before running, inside a transaction, after a `pg_dump` of each database:
1. Stop the application containers (`make down` keeps volumes), so no outbox or consumer is mid-flight.
2. Delete child rows first (trades → orders, ledger/holdings/reservations → accounts, …), then the users.
3. Audit logs: `live_audit_log` is append-only, enforced by a PostgreSQL trigger, and the learning
   readiness log is hash-chained. Deleting a test user's whole chain does not break anyone else's (the
   chains are per user), but it does mean disabling the append-only trigger for the duration. I would
   **not** delete audit rows; they can stay as orphans with no account behind them.
4. Run the reconciliation (`/api/admin/reconciliation`) and the readiness/live chain checks afterwards.

Consequences of C: not reversible except from the dump. Demo market data, the seeded accounts and
account 7 are untouched. If the classification is wrong for any account, that account's orders,
portfolio and progress are gone, so you should review the generated id list before it runs.

To keep new test accounts from accumulating, the Playwright specs could use a dedicated domain (for
example `@e2e.tradeflow.dev`). Option B or C then becomes a one-line pattern match.

**Decision needed:** A, B or C. I have not changed any data.
