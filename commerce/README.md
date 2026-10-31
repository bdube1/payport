# Payport Commerce

Payment links, product pages and mini-stores for Payport merchants (Phase 1 of the Payport Commerce brief).

## Why it is its own service

The owner's rule (31 October 2026): **if Payport Payments goes down, Commerce must stay available.** So Commerce has
its own process, port (8086), database (`payport_commerce_*`) and database user, which has no rights on any PSP table.

| When the PSP is down | What happens |
|---|---|
| Store, product and link pages | Served as normal, from Commerce's own database |
| A customer presses Pay | The order is saved; the page says payments are briefly unavailable and offers "Try again" |
| Payment results | Wait in the PSP's event stream; Commerce reads on from where it stopped |
| Merchants already signed in | Keep working (products, links, orders); refunds wait for the PSP |
| Signing in to the store | Needs the PSP once (it confirms who the merchant portal login belongs to) |

## How money stays with the PSP

- Customers always pay on the PSP's hosted checkout; card details never touch Commerce pages.
- An order is **paid only when the PSP says so** (its event stream, checked against its status). An unclear provider
  answer shows "being confirmed", never paid. A late or repeated confirmation changes nothing.
- Each try at paying is one PSP checkout (`CO-XXXXXXXX-1`, `-2`, ...). A double click reuses the same checkout; a
  payment that arrives late on an earlier try still marks the order paid.
- Refunds: the merchant refunds its own customer **from its own balance**; the PSP lists the payout in the central
  reversal register, and the refund shows "requested" until Finance records the payout.
- Commerce never writes the ledger and never reads PSP tables. It talks to the PSP only through the PSP's Commerce
  API (`/api/v1/commerce/*`, service key `PAYPORT_COMMERCE_KEY`).

## Merchant portal

The merchant portal's **Store** section uses `/api/commerce/*` here. It sends its own login token once to
`/api/commerce/session`; Commerce asks the PSP who it is and gives a 12-hour Commerce session.

## Set-up (staging)

```bash
# 1. Database and user (edit the password first)
mysql -u root -p < /opt/payport/staging/commerce/scripts/create-database.sql
# 2. Settings
cd /opt/payport/staging/commerce && cp scripts/env.commerce.example .env.commerce && chmod 600 .env.commerce   # fill it in
#    and put the same PAYPORT_COMMERCE_KEY in /opt/payport/staging/.env.staging, then restart the gateway
# 3. Build and start
mvn clean package -DskipTests && scripts/start-commerce.sh
# 4. Caddy: copy scripts/store.caddy to /etc/caddy/, import it, point DNS for store-staging.payport.africa here, reload
```

Custom domains: see `scripts/store-domains.caddy` (Caddy on-demand TLS, asking Commerce which domains are verified).

Deploying an update (stops at the first failure):

```bash
set -e
mysqldump -u root -p payport_commerce_staging > /opt/payport/backups/commerce-$(date +%F-%H%M).sql   # migrations run on start
cd /opt/payport/staging && git pull
cd commerce && mvn clean package -DskipTests      # the same commit already passed CI
scripts/stop-commerce.sh
scripts/start-commerce.sh                          # waits for the readiness check, exits non-zero if it fails
```

Memory: about 300 MB (`-Xmx384m`). The staging server is already short of memory; a second server is better.
