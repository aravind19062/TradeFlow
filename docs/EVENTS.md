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

Events consumed by portfolio-service are keyed by user id so one user's events stay ordered
within a topic. Ordering across topics is not guaranteed; the state machines below make that safe.

## Order saga

```
order-service                         portfolio-service
POST /api/orders ── orders.placed ──▶ reserve cash/shares
     PENDING                          ├─ ok   ── orders.reserved ──▶ MARKET: fill at accepted quote → FILLED
                                      │                              LIMIT: fill if marketable, else OPEN
                                      └─ fail ── orders.rejected ──▶ REJECTED
OPEN ── prices.updated (marketable) ──▶ FILLED ── trades.executed ──▶ settle: cash/holdings/ledger
OPEN ── POST /cancel ──▶ CANCELLED ── orders.cancelled ──▶ release reservation
```

* A PENDING order cannot be cancelled (its reservation is in flight), so a cancel can never
  race a reservation.
* FILLED and CANCELLED are mutually exclusive: the order row has an optimistic-lock version.
* A BUY never fills above its reservation price, so settlement never needs more cash than
  was reserved; if it ever did, portfolio-service raises `InconsistentStateException`.

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
