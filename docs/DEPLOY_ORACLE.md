# Deploying TradeFlow on Oracle Cloud (free) with Tailscale

The result: TradeFlow runs on a free Oracle ARM server, reachable only from your own devices at an
HTTPS address like `https://tradeflow.your-tailnet.ts.net`. Nothing is open to the public internet.

You run these steps yourself. Nothing here has been run on a real Oracle server yet, so expect to
adjust small details.

## 1. Create the server

1. Sign up at <https://www.oracle.com/cloud/free/>. Oracle asks for a card to verify you, and Always
   Free resources are not charged. Don't upgrade the account unless you mean to.
2. Create a compute instance:
   * Image: **Ubuntu 24.04** (the aarch64 build).
   * Shape: **VM.Standard.A1.Flex**, 2 OCPUs and 12 GB memory.
   * Boot volume: 100 GB.
   * Add your SSH public key.
3. If Oracle says "out of capacity", try another availability domain or retry later.

Oracle can reclaim Always Free instances that sit idle, so check its current policy.

## 2. Set up the server

```bash
ssh ubuntu@<public-ip>
git clone https://github.com/aravind19062/TradeFlow.git
cd TradeFlow
git checkout feature/dual-mode-trading
sudo deploy/bootstrap.sh
sudo tailscale up --ssh
```

Open the link Tailscale prints and sign in. Install Tailscale on your laptop and phone with the same
account. If the repository is private, sign in with a GitHub token or deploy key to clone it.

In the [Tailscale admin console](https://login.tailscale.com/admin/dns), turn on **MagicDNS** and
**HTTPS Certificates**. Then log out of the server and back in, and find your server's name:

```bash
tailscale status --json | grep -m1 DNSName
```

## 3. Start TradeFlow

Use that name, without the trailing dot:

```bash
deploy/generate-env.sh tradeflow.your-tailnet.ts.net
make up-server
```

The first build takes a while. Check it with `make ps`, then open
`https://tradeflow.your-tailnet.ts.net` on a device signed in to Tailscale.

The seeded `demo@tradeflow.dev` and `admin@tradeflow.dev` accounts use the random
`TRADEFLOW_DEMO_PASSWORD` in `.env`. Your Mac's data isn't copied, so this starts empty. Your passkeys
and any authenticator enrolled on the Mac's stack don't carry over.

Monitoring is at `https://tradeflow.your-tailnet.ts.net:8443`. Sign in as `admin` with
`GRAFANA_ADMIN_PASSWORD` from `.env`, and open the **TradeFlow overview** dashboard.

## 4. Close the public SSH port

Only after `ssh ubuntu@tradeflow` works **over Tailscale**:

```bash
sudo ufw delete allow 22/tcp
```

In the Oracle console, you can also remove the SSH rule from the subnet's security list.

## 5. Connect Fyers

Set the redirect URL of your Fyers app to `https://<your-host>/live/broker/callback`, put the app
details in `.env`, set `TRADEFLOW_LIVE_BROKER=fyers`, and run
`docker compose -f docker-compose.yml -f docker-compose.oracle.yml --profile apps up -d live-trading-service`.

Real orders (phase 7) would need a static IP registered with the broker under SEBI's 2026 rules.
Tailscale doesn't provide that, so check Oracle's reserved public IP then.

## Backups

```bash
deploy/backup.sh
```

This saves all databases and the encryption keys to `~/tradeflow-backups`, keeping 14 days. It's
private data, so copy it off the server somewhere safe. To run it nightly at 02:30, add this with
`crontab -e`:

```
30 2 * * * cd $HOME/TradeFlow && deploy/backup.sh >> $HOME/tradeflow-backups/backup.log 2>&1
```

## Updating

```bash
git pull
make up-server
```

## If something goes wrong

| Symptom | Check |
|---|---|
| A container keeps restarting | `docker compose logs --tail=100 <service>` |
| The HTTPS address doesn't load | `tailscale serve status`, and that this device is on Tailscale |
| "out of capacity" when creating the server | Try another availability domain, or retry later |
| Passkeys fail | `TRADEFLOW_PASSKEY_RP_ID` must be the exact host, and `TRADEFLOW_PASSKEY_ORIGINS` the exact `https://` address |
