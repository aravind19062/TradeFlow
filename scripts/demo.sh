#!/usr/bin/env bash
# TradeFlow scripted walkthrough. Requires curl and jq, and all four services running
# (make up, or make infra-up + services from the IDE, or make run-h2).
# Uses the seeded DEMO users; all prices are simulated.
set -euo pipefail

USER_URL=${USER_URL:-http://localhost:8081}
ORDER_URL=${ORDER_URL:-http://localhost:8080}
PORTFOLIO_URL=${PORTFOLIO_URL:-http://localhost:8082}
MARKET_URL=${MARKET_URL:-http://localhost:8083}
DEMO_PASSWORD=${TRADEFLOW_DEMO_PASSWORD:-Demo@12345}
RUN_ID=$(date +%s)

command -v jq >/dev/null || { echo "jq is required (brew install jq)"; exit 1; }
step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
json() { curl -sS -H 'Content-Type: application/json' "$@"; }

wait_for_order() {   # $1 order id, $2 expected status
  for _ in $(seq 1 40); do
    s=$(json "$ORDER_URL/api/orders/$1" -H "$AUTH" | jq -r .order.status)
    [ "$s" = "$2" ] && return 0
    sleep 0.5
  done
  echo "order $1 did not reach $2 (last: $s)"; return 1
}

step "Health"
for u in "$USER_URL" "$MARKET_URL" "$ORDER_URL" "$PORTFOLIO_URL"; do
  printf '%-24s %s\n' "$u" "$(curl -s "$u/actuator/health" | jq -r .status)"
done

step "Log in as the demo trader and the demo admin"
TOKEN=$(json "$USER_URL/api/auth/login" -d "{\"email\":\"demo@tradeflow.dev\",\"password\":\"$DEMO_PASSWORD\"}" | jq -r .accessToken)
ADMIN=$(json "$USER_URL/api/auth/login" -d "{\"email\":\"admin@tradeflow.dev\",\"password\":\"$DEMO_PASSWORD\"}" | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"
echo "got tokens (${#TOKEN} and ${#ADMIN} chars)"

step "Simulated quotes (public)"
json "$MARKET_URL/api/market/quotes?symbols=TCS,INFY,HDFCBANK" | jq -r '.[] | "\(.symbol)\t\(.price)\t\(.changeRatio)\t\(.source)"'

step "Watchlist: add TCS and INFY"
json -X PUT "$MARKET_URL/api/watchlist/TCS" -H "$AUTH" >/dev/null
json -X PUT "$MARKET_URL/api/watchlist/INFY" -H "$AUTH" | jq -c '[.[] | {symbol, price}]'

step "Portfolio before trading (account is opened asynchronously from users.registered)"
for _ in $(seq 1 40); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$PORTFOLIO_URL/api/portfolio/summary" -H "$AUTH")" = 200 ] && break
  sleep 0.5
done
json "$PORTFOLIO_URL/api/portfolio/summary" -H "$AUTH" | jq -c '{cashBalance, reservedCash, totalValue, holdings: (.holdings | length)}'

step "MARKET BUY 10 TCS (idempotency key demo-buy-$RUN_ID) -> 202 PENDING"
BUY=$(json "$ORDER_URL/api/orders" -H "$AUTH" -d "{\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":10,\"clientOrderId\":\"demo-buy-$RUN_ID\"}")
echo "$BUY" | jq -c '{id, status, reservationPrice}'
BUY_ID=$(echo "$BUY" | jq -r .id)
wait_for_order "$BUY_ID" FILLED
json "$ORDER_URL/api/orders/$BUY_ID" -H "$AUTH" | jq -c '{status: .order.status, history: [.history[].to], fill: .trades[0] | {price, grossAmount}}'

step "Same request again -> 200 with the same order (no duplicate)"
json -o /dev/null -w '%{http_code}\n' "$ORDER_URL/api/orders" -H "$AUTH" -d "{\"symbol\":\"TCS\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":10,\"clientOrderId\":\"demo-buy-$RUN_ID\"}"

step "Oversized BUY -> REJECTED INSUFFICIENT_FUNDS"
BIG=$(json "$ORDER_URL/api/orders" -H "$AUTH" -d '{"symbol":"MARUTI","side":"BUY","type":"MARKET","quantity":900}' | jq -r .id)
wait_for_order "$BIG" REJECTED
json "$ORDER_URL/api/orders/$BIG" -H "$AUTH" | jq -c '.order | {status, rejectCode, statusReason}'

step "Resting LIMIT BUY far below the market -> OPEN, cash reserved; then cancel -> released"
LIM=$(json "$ORDER_URL/api/orders" -H "$AUTH" -d '{"symbol":"INFY","side":"BUY","type":"LIMIT","quantity":5,"limitPrice":100.00}' | jq -r .id)
wait_for_order "$LIM" OPEN
json "$PORTFOLIO_URL/api/portfolio/summary" -H "$AUTH" | jq -c '{reservedCash}'
json -X POST "$ORDER_URL/api/orders/$LIM/cancel" -H "$AUTH" | jq -c '{status}'
sleep 1.5
json "$PORTFOLIO_URL/api/portfolio/summary" -H "$AUTH" | jq -c '{reservedCash}'

step "Admin advances the market 1 tick, then MARKET SELL 4 TCS -> realized P&L"
json -X POST "$MARKET_URL/api/market/simulation/advance" -H "Authorization: Bearer $ADMIN" | jq -c '{currentTick}'
sleep 1
SELL=$(json "$ORDER_URL/api/orders" -H "$AUTH" -d '{"symbol":"TCS","side":"SELL","type":"MARKET","quantity":4}' | jq -r .id)
wait_for_order "$SELL" FILLED
sleep 1

step "Admin advances the simulated market 5 ticks"
json -X POST "$MARKET_URL/api/market/simulation/advance?ticks=5" -H "Authorization: Bearer $ADMIN" | jq -c .

step "Portfolio, risk and performance"
json "$PORTFOLIO_URL/api/portfolio/summary" -H "$AUTH" | jq -c '{cashBalance, totalValue, realizedPnl, unrealizedPnl, holdings: [.holdings[] | {symbol, quantity, averageCost, marketPrice}]}'
json "$PORTFOLIO_URL/api/portfolio/risk" -H "$AUTH" | jq -c '{largestPosition, largestPositionWeight, herfindahlIndex, maxDrawdownRatio, warnings}'
json "$PORTFOLIO_URL/api/portfolio/performance" -H "$AUTH" | jq -c '{startValue, endValue, changeRatio, maxDrawdownRatio, pointCount}'

step "AI portfolio assistant"
for q in "What is my current portfolio worth?" "Which holdings have the highest concentration?" \
         "What is the status of order $BUY_ID?" "Buy 100 RELIANCE for me"; do
  echo "Q: $q"
  json "$PORTFOLIO_URL/api/assistant/ask" -H "$AUTH" -d "{\"question\":\"$q\"}" | jq -r '"[\(.mode)] \(.answer)\n  facts: \([.facts[].tool] | join(", "))\n  notice: \(.notice // "-")"'
done

step "Reconciliation (admin, all accounts)"
json "$PORTFOLIO_URL/api/admin/reconciliation" -H "Authorization: Bearer $ADMIN" | jq -c '{accountsChecked, consistent, issues}'

printf '\nDone. Swagger UIs: %s/swagger-ui.html (and :8081, :8082, :8083)\n' "$ORDER_URL"
