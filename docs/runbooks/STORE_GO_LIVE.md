# PayPort Store: going live

The owner approved going live on 31 Oct 2026. These are the steps on the server, in order. Nothing here moves money until the last step,
and the last step can be undone in one click.

## 1. Before you start

| Check | How |
|---|---|
| A tested backup of both databases (gateway and `payport_commerce`) | `mysqldump --single-transaction` of each, then restore one into a scratch database to prove it opens |
| Staging journey passed | Run the journey in section 5 on staging first, with sandbox numbers |
| Settlement stays in parallel run | `SETTLEMENT_MODE=PARALLEL` and `SETTLEMENT_AUTOMATION_ENABLED=false` in the gateway's env file |
| Monitoring is running | `ops/observability` (Prometheus, Alertmanager, Grafana) with the store job and alerts from this release |

## 2. Provider credentials (gateway env file, mode 600)

Add only the providers you have signed contracts and production credentials for. Leave the others empty: a method without
credentials stays unavailable to live store payments.

| Method | Settings |
|---|---|
| EcoCash | `ECOCASH_MERCHANT_CODE`, `ECOCASH_MERCHANT_PIN`, `ECOCASH_MERCHANT_NUMBER`, `ECOCASH_TERMINAL_ID`, `ECOCASH_USERNAME`, `ECOCASH_PASSWORD` |
| OneMoney | `ONEMONEY_BASE_URL` (production address from NetOne), `ONEMONEY_MERCHANT_NO`, `ONEMONEY_ENCRYPT_KEY_ID`, `ONEMONEY_PLATFORM_PUBLIC_KEY`, `ONEMONEY_MERCHANT_PRIVATE_KEY` |
| Omari | `OMARI_BASE_URL` (production, not the UAT default), `OMARI_API_KEY` |
| InnBucks | `INNBUCKS_BASE_URL`, `INNBUCKS_API_KEY`, `INNBUCKS_USERNAME`, `INNBUCKS_PASSWORD` |
| Card (ZimSwitch) | `ZIMSWITCH_ENTITY_ID`, `ZIMSWITCH_BEARER_TOKEN`, and the production base URL from the acquirer |
| SMS (order texts, offers) | `AFRICAS_TALKING_USERNAME` (not `sandbox`), `AFRICAS_TALKING_API_KEY`, `AFRICAS_TALKING_SENDER_ID` |

Restart the gateway after editing (`stop` then `start`). Never paste these values into chat, tickets or commits.

## 3. Check

```
API=https://api.payport.africa STORE=https://store.payport.africa ADMIN_TOKEN=<admin login token> \
  ENV_FILE=/opt/payport/gateway/.env scripts/golive/check-store-live.sh
```

It reads only; it prints which providers are ready and which setting names are missing, never their values. Fix every
`[todo]` line before going on.

## 4. Switch store payments to live

Ops portal > Platform > Channels and product lines > **Store payments** > **Switch to live** (ADMIN only). Write the
approval in the box (who approved and when). Every switch is in the audit log.

From then on new store checkouts are live; orders already placed keep the mode they were placed in. Each merchant still
chooses its own methods (Store > Settings > Payment methods).

## 5. First live journey (a small real payment)

1. A staff member's own store: a 1.00 item, pickup.
2. Buy it on the website with EcoCash (or another ready method): the order shows **Paid**; the merchant gets the text.
3. Repeat on the app, WhatsApp and USSD (*699# > Shop).
4. Refund it from the merchant portal: the refund appears in Customer care > Reversals, awaiting payout. Pay it out as usual.
5. Watch Grafana for 30 minutes: no store alerts.

## 6. Watch

| Alert | Meaning | First action |
|---|---|---|
| StoreDown | Commerce is not answering | `commerce/scripts/stop-commerce.sh`, then `start-commerce.sh`; read `commerce.log` |
| StoreCannotReachPayments | Commerce cannot reach the gateway | Same `PAYPORT_COMMERCE_KEY` on both sides; gateway up; orders are kept and pay when it is back |
| StorePaymentEventsBehind | Payment results not read for 10 minutes | As above; results are applied when reading resumes |
| StorePaymentsStuck | Payments "being confirmed" for over 30 minutes | Check the provider in Operations > Transactions; the PSP's reconciliation settles them |
| StoreFailedPaymentsHigh | More failures than successes in an hour | Check the provider's status; switch the failing method off in the ops portal |

## 7. Undo (rollback)

1. **Payments back to sandbox:** Store payments > **Back to sandbox** (immediate; no new live checkouts).
2. **One method misbehaving:** switch that method off in the ops portal (Payment methods); the others carry on.
3. **The store itself:** `commerce/scripts/stop-commerce.sh`. Payments, the app's other services, USSD and WhatsApp keep working; the Shop menu says the store is not available.
4. **A bad release:** `git checkout <previous tag>`, rebuild, restart. Database changes in this release only add tables and columns, so the previous build runs on the new database.
5. **Data:** restore from the backup taken in step 1 only on the owner's decision; live payments made since then stay in the PSP's records.
