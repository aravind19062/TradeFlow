# Limit orders

A **limit order** sets the worst price you will accept:

* **Buy limit at ₹100:** buy at ₹100 or lower, never higher.
* **Sell limit at ₹120:** sell at ₹120 or higher, never lower.

## What you get

* **Price control.** You will never pay more (or receive less) than your limit.
* **No execution guarantee.** If the market never reaches your price, the order never fills. It rests
  in the order book until it fills, you cancel it, or it expires.

## Marketable and resting limit orders

* A buy limit **above** the current ask (or a sell limit **below** the current bid) is *marketable*: it
  can execute immediately, at the best available price up to your limit.
* A buy limit **below** the ask (or a sell limit **above** the bid) *rests* and waits.

## Validity

Orders have a validity period. A **day** order expires at the end of the session if unfilled. TradeFlow's
demo expires resting limit orders after one day.

## Cancelling

You can cancel an order that has not been filled. Once it is filled, it is a trade, and cancelling is no
longer possible; you would need a new, opposite order.

> **Key idea:** a limit order trades certainty of price for uncertainty of execution.
