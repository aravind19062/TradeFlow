# Position sizing

Position sizing answers: **how many shares** should I buy so that, if my stop-loss is hit, I lose no more
than I planned?

## The formula

```
risk per share   = entry price − stop price
amount at risk   = account value × risk fraction
quantity         = floor(amount at risk / risk per share)
```

## Example

* Account value: ₹5,00,000
* Risk fraction: 1% → amount at risk ₹5,000
* Entry ₹250, stop ₹235 → risk per share ₹15
* Quantity = floor(5,000 / 15) = **333 shares** (position value ≈ ₹83,250)

If the stop fills at ₹235, the loss is about 333 × 15 = ₹4,995, plus costs and any slippage.

## Check the position weight too

The formula limits loss **at the stop**, not in a gap. 333 shares at ₹250 is about 17% of the account. If
the share gaps down 40% overnight, the loss would be about ₹33,300, far more than 1%. Many traders also cap
the position's weight in the portfolio.

## Why it matters

Consistent sizing means no single trade can do outsized damage, and a string of losses remains
survivable. Increasing size to "win back" losses does the opposite.

## Practice in the demo

The position-sizing exercise gives you a scenario. Calculate the quantity, then place that exact demo
order. TradeFlow checks the quantity, not whether the trade makes money.
