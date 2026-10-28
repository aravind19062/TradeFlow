# TradeFlow event contract (schema version 1)

All events are JSON strings on Kafka (String key, String value). The Java records live in
`tradeflow-common/src/main/java/com/luffy/tradeflow/common/events/Events.java`.

## Envelope

```json
{
  "eventId": "2f4eda8c-ef40-44b0-86ff-085e2213cbc4",
  "eventType": "orders.reserved",
  "schemaVersion": 1,
  "occurredAt": "2026-10-27T12:45:13.612588Z",
  "producer": "tradeflow-portfolio-service",
  "aggregateId": "fa20fe2e-84c4-4c3b-838d-af6cbcfb7519",
  "payload": { "...": "event specific" }
}
```

| Field | Meaning |
|---|---|
| `eventId` | Unique, stable id. Consumers de-duplicate on it (`processed_events` table). |
| `eventType` | Always equal to the topic name. |
| `schemaVersion` | Payload version. Consumers reject versions they do not support (→ DLT). |
| `occurredAt` | Time the producing transaction created the event. |
| `aggregateId` | The entity the event is about; also the Kafka message key. |

Decimals are JSON numbers and are read as `BigDecimal` (never `double`).

## Topics

| Topic | Producer → consumer | Key | Payload | Meaning |
|---|---|---|---|---|
| `users.registered` | user → portfolio | userId | `userId, email, name` | Open a cash account with the simulated starting balance. |
| `orders.placed` | order → portfolio | userId | `orderId, userId, symbol, side, type, quantity, reservationPrice` | Reserve `quantity × reservationPrice` cash (BUY) or `quantity` shares (SELL). |
| `orders.reserved` | portfolio → order | orderId | `orderId, userId, reservedCash, reservedQuantity` | Reservation held; the order may execute. |
| `orders.rejected` | portfolio → order | orderId | `orderId, userId, reasonCode, reason` | `INSUFFICIENT_FUNDS`, `INSUFFICIENT_POSITION` or `ACCOUNT_NOT_FOUND`. |
| `orders.cancelled` | order → portfolio | userId | `orderId, userId, reason` | Release the order's reservation. |
| `trades.executed` | order → portfolio | userId | `tradeId, orderId, userId, symbol, side, quantity, price, grossAmount, executedAt` | Settle the fill against the reservation. |
| `prices.updated` | market-data → order | symbol | `symbol, price, tick, asOf, source` | New simulated tick; resting LIMIT orders may fill. |
| `trades.settled` | portfolio → order | orderId | `tradeId, orderId, userId` | The trade is booked. Re-sent for an already-booked trade, so a re-driven saga can complete. |
| `orders.released` | portfolio → order | orderId | `orderId, userId, outcome` | Reply to `orders.cancelled`: `RELEASED`, `NOTHING_TO_RELEASE` (tombstone recorded) or `ALREADY_SETTLED` (nothing undone). |

Events consumed by portfolio-service are keyed by user id so one user's events stay ordered
within a topic. Ordering across topics is not guaranteed; the state machines below make that safe.

## Order saga

Choreography with an explicit progress record (`order_sagas`) owned by order-service; a recovery job
acts on overdue steps.

| Saga state | Waiting for | On timeout |
|---|---|---|
| `AWAITING_RESERVATION` | `orders.reserved` / `orders.rejected` (2 min) | Order REJECTED (`RESERVATION_TIMEOUT`) and release requested |
| `RESTING` | marketable `prices.updated` or user cancel (time-to-live 1 day) | Order EXPIRED and release requested |
| `AWAITING_SETTLEMENT` | `trades.settled` (2 min) | Same trade re-sent (≤ 3 times), then `SETTLEMENT_FAILED`. Funds are **never** released. |
| `RELEASE_REQUESTED` | `orders.released` (2 min) | Release re-sent (≤ 3 times), then `RELEASE_FAILED` |

Final states: `COMPLETED`, `REJECTED`, `RELEASED`. Failed states (`SETTLEMENT_FAILED`, `RELEASE_FAILED`)
turn `orderPipeline` health DOWN; an admin retries them with `POST /api/admin/orders/sagas/{id}/retry`.

Rules that keep compensation safe:

* **Cancel timing:** a PENDING order cannot be cancelled by the user; its reservation is in flight.
  Timeouts handle that case instead.
* **Late reservations:** a reservation for an order that already ended triggers another release request.
  A release that arrives before its reservation leaves a tombstone, so the late reservation is refused
  (`ORDER_CANCELLED`). Reserve and release for the same user both lock the account row first
  (`SELECT … FOR UPDATE`), so they can never interleave and leave both a reservation and a tombstone.
* **Settled reservations:** portfolio never releases them, and answers `ALREADY_SETTLED`.
* **Fill vs cancel:** FILLED and CANCELLED/EXPIRED are mutually exclusive (optimistic lock on the order
  row).
* **Concurrent settlements:** settlements of the same order are serialized by a row lock on the
  reservation, and `ledger_entries.trade_id` is UNIQUE.
* **Cross-service check:** portfolio-service compares stale ACTIVE reservations with order-service. It
  releases leaked ones automatically and reports unsettled fills and orphans.

## Delivery guarantees

* **Producer side: transactional outbox.** The business change and the event are written in
  one database transaction (`outbox_events`); a poller publishes to Kafka with
  `acks=all` and idempotent producers. If Kafka is down the events wait in the outbox and the
  `outbox` health indicator turns DOWN after one minute.
* **Consumer side: idempotent consumer.** Each handler runs in one database transaction with an
  insert into `processed_events(event_id)`. The Kafka offset is committed after the
  transaction. A redelivered event is skipped; a failed one is retried in full.
* Net effect: at-least-once delivery with idempotent effects. This is **not** exactly-once.
  Two extra guards back it up: `ledger_entries.trade_id` is UNIQUE (a trade can never be
  booked twice, even if re-published with a new event id) and every reservation is keyed by
  order id.

## Failures and the dead-letter topics

| Failure | Handling |
|---|---|
| Transient (DB unavailable, optimistic-lock conflict) | Retried 3 times, 1 s apart. |
| Malformed JSON, wrong type, unsupported version, failed validation | `InvalidEventException`: not retried, sent to `<topic>.DLT`. |
| Well-formed but contradicts state (trade without reservation, quantity mismatch) | `InconsistentStateException`: not retried, sent to `<topic>.DLT`, logged at ERROR. |

DLT records keep the original payload and carry `kafka_dlt-exception-*` headers.
Inspect them with `make dlt T=trades.executed` or Kafka UI (http://localhost:8085).

## Versioning policy

* Adding an optional payload field is backward compatible (unknown fields are ignored).
* Removing/renaming a field or changing its meaning requires `schemaVersion: 2`. Producers
  then publish both versions (or a new topic) until every consumer supports v2.
