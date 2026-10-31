# The life of an order

An order is not a trade. Between clicking "buy" and owning shares, an order passes through several
states. Knowing them helps you read your order book correctly.

| State | Meaning |
|---|---|
| **Submitted / pending** | The order has been accepted for processing. Your broker is checking funds or shares (risk checks). |
| **Open** | The order is live at the exchange, waiting for a matching price (typically a resting limit order). |
| **Filled** | The order executed. A **trade** now exists, and it cannot be cancelled. |
| **Partially filled** | Part of the quantity executed; the rest may still be open. |
| **Rejected** | The order was refused, for example for insufficient funds, a price outside allowed bands, or an invalid quantity. |
| **Cancelled** | You (or the system) withdrew an unfilled order. |
| **Expired** | Its validity ended before it filled. |

## Reservations

While an order is open, your broker **blocks** the cash (for a buy) or the shares (for a sell). Blocked
funds are not available for other orders. Cancelling releases them.

## Rejections are information

A rejection is not an error to retry blindly. Read the reason. Insufficient funds, a price outside the
day's limits and a quantity outside allowed limits each need a different response.

## In TradeFlow's demo

Demo orders start **PENDING** while virtual cash or shares are reserved, then become **OPEN** (resting),
**FILLED**, **REJECTED**, **CANCELLED** or **EXPIRED**. The demo does not model partial fills.
