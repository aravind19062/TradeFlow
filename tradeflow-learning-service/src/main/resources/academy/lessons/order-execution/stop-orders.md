# Stop and stop-limit orders

A **stop order** stays dormant until the market reaches a **trigger price**, then becomes active.

## Sell stop (stop-loss)

You hold shares bought at ₹500. You place a **sell stop at ₹460**.

* While the price stays above ₹460, nothing happens.
* If the **bid falls to ₹460 or below**, the order triggers and becomes a **market sell**.
* Because it becomes a market order, it fills at the **next available bid**. In a fast fall or an
  overnight gap that can be well below ₹460. A stop is not a guaranteed exit price.

## Sell stop-limit

A **stop-limit** has two prices: the trigger and a limit. For example, trigger ₹460 and limit ₹455.

* When the bid reaches ₹460, the order becomes a **sell limit at ₹455**.
* It will never sell below ₹455, but if the price falls straight through ₹455 it may **not fill at all**,
  and you remain in the losing position.

| | Stop (market) | Stop-limit |
|---|---|---|
| After trigger | Market order | Limit order |
| Execution | Very likely | Not guaranteed |
| Price | Not guaranteed | Not worse than the limit |

## Practical notes

* Stops protect you only if they are in place **before** the move.
* Placing a stop very close to the current price can trigger on normal noise.
* Brokers may treat stop orders differently (validity, product types, exchange support). Check your
  broker's rules before relying on them.

## In TradeFlow's demo

The demo supports **sell** stop and stop-limit orders on shares you hold. Triggers are checked against
the simulated **bid** on each price tick. Buy stops are not supported because the demo is long-only.
