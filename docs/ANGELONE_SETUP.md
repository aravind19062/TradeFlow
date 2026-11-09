# Linking your Angel One account (phase 6, read-only)

Phase 6 lets TradeFlow **read** your Angel One account through Angel One's official SmartAPI:
funds and used margin, holdings, positions (intraday and delivery) and today's order book. It
**cannot place, modify or cancel orders**: the Angel One adapter refuses those calls without
contacting Angel One. Real orders are phase 7 and need your explicit go-ahead.

Angel One replaced Fyers on 2026-11-08 because you hold an Angel One trading account and no Fyers one.

## What TradeFlow needs, and what it never needs

| | Needed? | Where it goes |
|---|---|---|
| SmartAPI **API key** of your app | **Yes** | `TRADEFLOW_ANGELONE_API_KEY` in the git-ignored `.env` |
| App **redirect URL** registered at Angel One | **Yes** (set in the app, not in TradeFlow) | `http://localhost:3000/live/broker/callback` |
| SmartAPI app **secret key** | No | Keep it at Angel One; TradeFlow never uses it |
| Your Angel One **client password / PIN** | **Never** | You type it on Angel One's own page only |
| Your **TOTP** code or TOTP secret | **Never** | Same: Angel One's page only |
| Local IP, public IP, MAC address | Optional | `TRADEFLOW_ANGELONE_CLIENT_LOCAL_IP`, `..._CLIENT_PUBLIC_IP`, `..._MAC_ADDRESS` |

The last row: SmartAPI requests carry the caller's IPs and MAC address as headers
(`X-ClientLocalIP`, `X-ClientPublicIP`, `X-MACAddress`), as Angel One's official SDKs send them. If
unset, TradeFlow sends `127.0.0.1` and `00:00:00:00:00:00`. If Angel One rejects requests for this,
set the real values of the machine running TradeFlow. A **static IP** registered with Angel One is
required by SEBI's 2026 rules for API **orders** (phase 7), not for these reads.

## 1. Create a SmartAPI app (once)

1. Sign in at <https://smartapi.angelone.in> with your Angel One client ID and create an app.
2. **Redirect URL:** `http://localhost:3000/live/broker/callback` (exactly this; the deployment
   guide will use your HTTPS address instead).
3. Note the **API key**. You do not need to give TradeFlow the secret key.

## 2. Give the API key to the local stack

In `.env` in the repository root (git-ignored; copy `.env.example` if it doesn't exist):

```
TRADEFLOW_LIVE_BROKER=angelone
TRADEFLOW_ANGELONE_API_KEY=<your API key>
```

Containers read `.env` only when they are created, so recreate the live service afterwards:

```
docker compose --profile apps up -d --no-deps live-trading-service
```

If the key is missing, the service refuses to start (`docker compose logs live-trading-service`
says which value). Don't paste the key into chats, issues or commits. To go back to no broker, set
`TRADEFLOW_LIVE_BROKER=none` and recreate the service.

## 3. Link the account in TradeFlow

The live gate applies to linking, as designed: your TradeFlow account must be **QUALIFIED** and
have **two-factor authentication** on, and each link asks for your TradeFlow password and
authenticator code.

Then: **Live trading → Connect broker** → Angel One's own login page (client ID, PIN, TOTP there,
never in TradeFlow) → Angel One sends you back to `/live/broker/callback` → **Confirm and link**.
TradeFlow checks the token with Angel One (`getProfile`) before storing it, encrypted; only your
client code is kept from the profile.

## Known risks, checked against Angel One's own forum (2026-11-08)

* **Redirect login for new API keys.** An Angel One admin wrote that the publisher-login redirect
  "is valid for only the old API key. Redirect URL for new API key will be updated soon." If your new
  app does not redirect back to TradeFlow, this is why; TradeFlow cannot fix it. TradeFlow will not
  fall back to collecting your PIN and TOTP.
* **The `state` value.** TradeFlow sends a one-time `state` and requires it back, which protects
  the link against forgery. A forum thread reported Angel One not returning it (an admin later said
  it was fixed). If it is missing, the callback page shows "Linking stopped for your safety" and
  nothing is linked.
* **Field names** for holdings and orders come from Angel One's announcements and official SDK;
  those for funds and positions from responses published on Angel One's forum. Compare with your
  account (below) and tell me about any mismatch.

## Please check after linking (phase 6 gate: "read-only verified with your own account")

Compare with the Angel One app or web:

1. Available margin and used margin (TradeFlow shows `net` and `utiliseddebits`).
2. Every holding: symbol, quantity, average price.
3. Positions, including intraday ones, and the sign of short quantities.
4. Today's orders: statuses (complete, rejected, cancelled, open) and filled quantities.
5. After the session ends, the dashboard asks you to link again.
