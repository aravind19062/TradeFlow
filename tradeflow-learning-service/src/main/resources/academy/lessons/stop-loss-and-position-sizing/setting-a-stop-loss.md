# Setting a stop-loss

A **stop-loss** is the price at which you accept that a trade idea is wrong and exit. It can be a
resting stop order or a level you act on manually. Either way, decide it **before** entering.

## Choosing the level

A stop should sit where the trade idea is **invalidated**, not at an arbitrary percentage. Ask: at what
price would my reason for owning this share no longer hold?

* Too close, and normal fluctuation (noise) stops you out repeatedly.
* Too far, and each loss is large, so position size must be small.

## Stops are not guarantees

A stop (market) order fills at the next available price after triggering. Overnight gaps, trading halts
and fast markets can cause fills well beyond the stop. A stop-limit avoids a bad fill price but may not
fill at all.

## Do not move a stop further away

Widening a stop after the price moves against you turns a planned small loss into an unplanned large
one. Moving a stop **closer** to lock in some gain (trailing) is a different matter.

## Practice in the demo

In TradeFlow's demo you can place a **sell stop** or **sell stop-limit** on a holding and watch how it
behaves when the simulated bid reaches the trigger.
