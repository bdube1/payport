# Switching products on (owner's runbook)

_Everything in Phase 8 ships **off** and fails closed. Nothing here needs a code change: credentials go into
the deployment's environment, decisions are made in the ops console by the right people, and every step is
audited. Do the steps in order; each one refuses to run until the previous one is done._

## 0. The pattern for every product line

1. **Licence answer** (Channels & Product Lines → line → *Record licence decision*): a COMPLIANCE officer
   records GRANTED (with the licence or authorisation reference) or NOT_REQUIRED (with the basis); a
   **second** compliance officer approves. A REFUSED answer later switches the line and its channels off at once.
2. **Line on**: an ADMIN switches the line on, with a reason. Refused unless the licence answer is GRANTED / NOT_REQUIRED (database CHECK).
3. **Channel on** (if the line has one): ADMIN/OPS switches the channel on.
4. **Catalogue**: activate the payment method / product in *Products & Payment Methods*.
5. **Parameters**: confirm the line's compliance parameters in *Compliance → Rules & Parameters* (two compliance officers). Until then they are PROVISIONAL placeholders.

Switching anything **off** is always immediate (one person, reason required).

## 1. Wallets (line `WALLETS`)

| Step | Who / where |
|---|---|
| E-money licence answer | Compliance ×2, line `WALLETS` |
| Trust / safeguarding account | Markets & Currencies → the currency's settlement account must be the safeguarding account (or add it) |
| Limits per KYC tier | Parameter `WALLET.TIER_LIMITS` (max balance, daily outgoing, per tier and currency), compliance ×2 |
| Line on | ADMIN |
| Pay with wallet | Products & Payment Methods → activate `WALLET` |
| Top-ups | Products & Payment Methods → activate product `WALLET_TOPUP` (and its currencies) |
| Conversions between a customer's own wallets | Line `FX_CONVERSION` on (licence answer first) + an approved rate (Channels & Product Lines → FX rates, maker ≠ checker) |
| Optional timings | `WALLET.PAYMENT_AUTH_TTL_MINUTES`, `FX.QUOTE_TTL_SECONDS` |

Balances are the ledger's `CUSTOMER_WALLET` accounts; the ledger refuses any posting that would take one below zero.

## 2. Agents (line `AGENTS`, channel `AGENT`)

| Step | Who / where |
|---|---|
| Agent network authorisation answer | Compliance ×2, line `AGENTS` |
| Commission | Parameter `AGENT.COMMISSION_BPS` (per operation), or a rate per agent at approval |
| Fraud control | `AGENT.MAX_CASH_OUT_FAILURES_PER_HOUR`, `WALLET.CASH_OUT_CODE_TTL_MINUTES` |
| Line and channel on | ADMIN |
| Each agent | Merchant applies in the merchant portal (Agent page) → a COMPLIANCE officer approves in *Agents & Float* |
| Float | Agent deposits at PayPort's bank and declares the reference → FINANCE approves after seeing it on the statement (the person who recorded it cannot approve) |

## 3. POS terminals (line `POS_ACQUIRING`, channel `POS`)

| Step | Who / where |
|---|---|
| Licence / sponsor-acquirer answer | Compliance ×2, line `POS_ACQUIRING` |
| Line and channel on | ADMIN |
| Terminals | Each merchant registers terminals in the merchant portal (secret shown once); ops can disable any terminal in *POS & WhatsApp* |
| Card acceptance | Not bundled. Needs a card acquirer implementation (`CardAcquirer`) and PCI DSS scope; until then terminals take QR, wallet and mobile money and card requests answer "not available" |
| Clock window | `POS.SIGNATURE_WINDOW_SECONDS` |

Terminal request signing: `X-Terminal-Id`, `X-Timestamp` (epoch seconds), `X-Nonce` (unique), `X-Signature` =
hex HMAC-SHA256(secret, `METHOD\nPATH\nTIMESTAMP\nNONCE\nhex(SHA-256(body))`).

## 4. WhatsApp (line `WHATSAPP_CHANNEL`, channel `WHATSAPP`)

| Environment variable | From |
|---|---|
| `WHATSAPP_APP_SECRET` | Meta app → App secret (webhook signature) |
| `WHATSAPP_VERIFY_TOKEN` | Any long random string; enter the same in Meta's webhook settings |
| `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_ACCESS_TOKEN` | WhatsApp Business account (sender). Without them replies are recorded, not sent |

Webhook URL: `https://<api>/api/v1/whatsapp/webhook` (subscribe to `messages`). Then: licence answer → line on → channel on.
*POS & WhatsApp* shows whether each setting is present (never the values).

## 5. MTN MoMo and Airtel Money (new markets)

| Environment variable | Value |
|---|---|
| `MTN_MOMO_BASE_URL` | e.g. `https://proxy.momoapi.mtn.com` (sandbox: `https://sandbox.momodeveloper.mtn.com`) |
| `MTN_MOMO_SUBSCRIPTION_KEY`, `MTN_MOMO_API_USER`, `MTN_MOMO_API_KEY` | MTN partner portal (Collections product) |
| `MTN_MOMO_TARGET_ENVIRONMENT` | e.g. `mtnzambia` (sandbox: `sandbox`) |
| `MTN_MOMO_CALLBACK_URL` | `https://<api>/api/v1/callbacks/mtn-momo` |
| `AIRTEL_MONEY_BASE_URL` | e.g. `https://openapi.airtel.africa` (staging: `https://openapiuat.airtel.africa`) |
| `AIRTEL_MONEY_CLIENT_ID`, `AIRTEL_MONEY_CLIENT_SECRET` | Airtel Africa developer portal; set the callback to `https://<api>/api/v1/callbacks/airtel-money` |
| `payport.integrations.MTN_MOMO.currencies` (optional) | Widen beyond ZMW when another MTN market goes live, e.g. `ZMW,UGX` |

Then activate the payment method (`MTN_MOMO`, `AIRTEL_MONEY`) once the market and currency are live. Record
sandbox evidence in *Integrations* (submit / callback / status_query) before production traffic; the register
stays SPEC_ONLY until evidence is recorded.

## 6. Provider settlements on the bank statement

FINANCE proposes a rule per rail and currency (*Provider Settlement Rules*: counterparty and/or remittance text
the bank shows for that rail's settlement credit); a second FINANCE user or an ADMIN approves. From the next
camt.053 import, matching credits are booked Dr BANK_CASH · Cr PROVIDER_RECEIVABLE[rail] and traced as PROVIDER_SETTLEMENT.

## 7. Operations settings (Phase 9)

| Environment variable | Purpose |
|---|---|
| `PAYPORT_ENV` | `production` in production (localhost CORS origins and other development fallbacks are then off) |
| `PAYPORT_CORS_EXTRA_ORIGINS` | Extra https browser origins, comma-separated |
| `PAYPORT_METRICS_TOKEN` | Bearer token for `/actuator/prometheus`; unset = no metrics endpoint |
| Probes | `/actuator/health/liveness`, `/actuator/health/readiness` |
| `PAYPORT_DATA_KEY` | Required in production (sealed secrets: webhook secrets, POS terminal secrets) |
