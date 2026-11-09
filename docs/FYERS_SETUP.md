# Linking your Fyers account (phase 6, read-only)

Phase 6 lets TradeFlow **read** your Fyers account: available funds and used margin, holdings, open
positions and today's order book. It **cannot place, modify or cancel orders**: the Fyers adapter
refuses those calls without contacting Fyers, and order previews are refused too. Real orders are
phase 7 and need your explicit go-ahead.

## 1. Create a Fyers API app (once)

1. Sign in at <https://myapi.fyers.in> with your Fyers account and open the API dashboard.
2. Create an app (an **individual app**: only you can log in with it).
   * **Redirect URL:** `http://localhost:3000/live/broker/callback`
     (if Fyers refuses `localhost`, use `http://127.0.0.1:3000/live/broker/callback` and open TradeFlow
     at `http://127.0.0.1:3000` so the browser session matches).
   * **Permissions:** leave **order placement off**. Fyers itself recommends not granting trading
     permission to an app that does not need it. This is a second lock, at Fyers, on top of TradeFlow's.
3. Note the **App ID** (looks like `XY12345-100`) and the **Secret Key**.

## 2. Give the credentials to the local stack

Create or edit `.env` in the repository root (it is git-ignored; copy `.env.example` if it doesn't exist):

```
TRADEFLOW_LIVE_BROKER=fyers
TRADEFLOW_FYERS_APP_ID=XY12345-100
TRADEFLOW_FYERS_SECRET_KEY=<your secret key>
TRADEFLOW_FYERS_REDIRECT_URI=http://localhost:3000/live/broker/callback
```

Then restart only the live service:

```
docker compose --profile apps up -d --no-deps live-trading-service
```

If a value is missing the service refuses to start (`docker compose logs live-trading-service` says
which). Don't paste the secret into chats, issues or commits. To go back to no broker, set
`TRADEFLOW_LIVE_BROKER=none` and restart the service.

## 3. Link the account in TradeFlow

The live gate applies to linking, as designed:

* your TradeFlow account must be **QUALIFIED** in Readiness (Academy, exercises, assessment,
  acknowledgement) and have **two-factor authentication** on;
* each link asks for your TradeFlow password and authenticator code (step-up).

Then: **Live trading → Connect broker** → you are sent to Fyers' own login page (TradeFlow never sees
your Fyers password, PIN or TOTP) → Fyers sends you back to `/live/broker/callback` → **Confirm and link**.

The dashboard then shows *Your broker account (read-only)* with funds, holdings, positions and orders.

## What to expect

* **Daily re-link.** Fyers access tokens expire every day at 06:30 IST. TradeFlow does not keep a
  refresh token, because refreshing needs your Fyers PIN, which TradeFlow must never handle. After
  06:30 the dashboard shows the account as not linked; click Connect broker again. (If Fyers ends the
  session earlier, for example after a logout on Fyers, reads answer "Your Fyers session has ended":
  disconnect and link again.)
* **Rate limits.** Fyers blocks an app for the rest of the day if its per-minute limit is exceeded
  more than three times. TradeFlow stays below the published limits and refuses locally before that
  ("Too many requests to Fyers"), and the account panel only loads on open and on Refresh.
* **Disconnect** logs the token out at Fyers and erases TradeFlow's encrypted copy. It never cancels
  anything at Fyers.
* **Static IP.** SEBI's 2026 rules require API *orders* to come from a whitelisted static IP. Reads
  are not orders; this matters for phase 7 (see DUAL_MODE_TRADING.md).

## Please check after linking (phase 6 gate: "read-only verified with your own account")

Compare with the Fyers app or web:

1. Available balance and used margin match the Fyers funds page.
2. Every holding (symbol, quantity, average cost) matches.
3. Open positions, including the sign of short quantities, match.
4. Today's orders: statuses (filled, rejected, cancelled, pending) and filled quantities match.
5. After 06:30 IST the next day, the dashboard asks you to link again.

Tell me about any mismatch; the field mapping was written from Fyers' API v3 documentation and
tested against those documented response shapes, not yet against a live account.
