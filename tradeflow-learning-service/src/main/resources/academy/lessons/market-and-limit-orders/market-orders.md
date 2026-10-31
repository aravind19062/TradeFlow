# Market orders

A **market order** says: "buy (or sell) this quantity now, at the best price available."

## What you get

* **Speed and near-certainty of execution.** In a liquid share during market hours, a market order
  usually executes almost immediately.
* **No price guarantee.** You accept whatever price the market offers at that moment. A market buy is
  filled at the best available **ask** (selling) price; a market sell at the best available **bid**
  (buying) price. The next module explains bid and ask.

## When the price can surprise you

* **Fast markets.** Around news, prices can move between the moment you click and the moment the order
  reaches the exchange.
* **Thin liquidity.** If few shares are offered near the last price, a large market order can "walk the
  book" and fill at progressively worse prices.
* **Gaps.** Prices can open far from the previous close after overnight news.

## In TradeFlow's demo

Demo market orders fill against the simulated **ask** (buys) or **bid** (sells) at the moment
TradeFlow accepts the order. The simulated market has a single quote and no order book depth, so it
cannot show walking the book. Real markets can.

> **Key idea:** a market order trades certainty of execution for uncertainty of price.
