# Bid, ask and the spread

At any moment a traded share has two prices, not one:

* **Bid:** the highest price a buyer is currently willing to pay. If you **sell** immediately, you
  receive the bid.
* **Ask** (or offer): the lowest price a seller is currently willing to accept. If you **buy**
  immediately, you pay the ask.

The **spread** is `ask − bid`. The **last traded price** you see on a chart is simply where the most recent
trade happened. It may sit between the two, at one of them, or be stale.

## A worked example

Bid ₹99.90, ask ₹100.10, so the spread is ₹0.20.

* Buy 100 shares at market: you pay about ₹100.10 × 100 = ₹10,010.
* Sell them immediately at market: you receive about ₹99.90 × 100 = ₹9,990.
* You have lost ₹20 before any price movement and before charges.

## The mid price

The **mid** is `(bid + ask) / 2`, here ₹100.00. Portfolios are often *valued* at the last price or the
mid, but you can only *trade* at the bid or ask.

## In TradeFlow's demo

Every simulated quote shows a bid and an ask around the simulated mid price. Market buys fill at the ask
and market sells at the bid, so you can see the spread cost in your own demo trades.
