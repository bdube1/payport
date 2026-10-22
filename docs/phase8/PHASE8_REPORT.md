# Phase 8: additional products, each behind its licensing question

_Branch `phase2-payment-lifecycle`, 2026-10-16. Roadmap: "Wallets, agents, QR (via the National Switch scheme), USSD, WhatsApp, POS, FX conversions, **each only after its licensing question is answered**."_

**Status: built and tested; everything ships switched off.** The owner records the licence answers in the
portal, configures credentials, and switches each line on after internal checks. How: [`docs/runbooks/SWITCH_ON.md`](../runbooks/SWITCH_ON.md).

## 1. The licence gate

`service_lines` (V51) holds one row per product line with its licensing question, licence status (NOT_ASSESSED, APPLIED, GRANTED, NOT_REQUIRED, REFUSED), reference and regulator.

- A licence decision is a request by one COMPLIANCE officer that a second approves; GRANTED or APPLIED needs a licence or application reference.
- A line (and any channel gated on it) can be switched on only when the status is GRANTED or NOT_REQUIRED — enforced in code and by a database CHECK.
- Withdrawing a licence (REFUSED) switches the line and its channels off at once.
- The licence status is never set by code or migration: it is the owner's decision, recorded in the portal.

| Line | Build | Switch-on needs |
|---|---|---|
| QR payments | **Built** | Licence answer; national QR scheme details for interoperability |
| FX quotes and conversions | **Built** (quotes, and conversions between a customer's own wallets) | Licence answer; approved rates (maker ≠ checker) |
| USSD | **Built** (channel, not licence-gated) | Aggregator secret (`payport.ussd.secret`) |
| Wallets | **Built** | Licence answer; tier limits confirmed; `WALLET` method and `WALLET_TOPUP` product activated |
| Agents | **Built** | Licence answer; commission confirmed; each agent approved by compliance |
| WhatsApp | **Built** | Licence answer; Meta app secret, verify token, sender number and token |
| POS | **Built** (QR / wallet / mobile money at terminals; cards fail closed) | Licence answer; a card acquirer implementation for cards |
| MTN MoMo, Airtel Money | **Built** (adapters) | Market and currency live; credentials; sandbox evidence |

## 2. What was built (V51, V52)

**Wallets** (`wallets/*`)
- The balance **is** the ledger balance of the wallet's `CUSTOMER_WALLET` account. The ledger refuses, under row locks, any posting that would take it below zero, so concurrent spends cannot overdraw a wallet (tested: 30 simultaneous transfers against money for 10 → exactly 10).
- Every movement: lock the wallets in id order, check status and tier limits (`WALLET.TIER_LIMITS`: maximum balance and daily outgoing per tier and currency), post **one** balanced journal on the same transaction as the operation rows (they commit together), record the operations; idempotent per operation reference.
- Top-up is a product (`WALLET_TOPUP`) paid on any rail; delivery credits the wallet (Dr PROVIDER_RECEIVABLE[rail] · Cr CUSTOMER_WALLET). The wallet is checked before the customer pays.
- Pay with wallet (`WALLET` method): only with a PIN-confirmed authorisation for that payment; the payment then follows the normal lifecycle and is confirmed through the same status-query path as every rail. A guest who picks WALLET is refused with nothing taken. Also pays a merchant's hosted checkout.
- Transfers by phone or wallet number (PIN, idempotent `clientRef`); conversions between a holder's own wallets at an approved rate (quote with expiry, one journal through `FX_CLEARING`, executed once); refunds of wallet payments back to the wallet (finance, closes the payment REFUNDED).
- Ops: search, statement, freeze (ops; immediate) / unfreeze (compliance or admin), KYC tier (compliance), totals.

**Agents** (`agents/*`)
- A merchant applies; a COMPLIANCE officer approves (with due-diligence evidence and an optional own commission rate).
- Float: the agent deposits at PayPort's bank and declares the reference; finance approves after seeing it on the statement (database CHECK: recorder ≠ approver; a bank reference is used once) → Dr BANK_CASH · Cr AGENT_FLOAT.
- Cash-in: Dr AGENT_FLOAT · Cr CUSTOMER_WALLET; cash-out against the holder's one-time code (hash only, single use, expiry, per-agent wrong-code limit): Dr CUSTOMER_WALLET · Cr AGENT_FLOAT. Commission in the same journal (Dr AGENT_COMMISSION_EXPENSE · Cr AGENT_FLOAT). AGENT_FLOAT cannot go negative.

**POS** (`pos/*`)
- Terminals registered by the merchant; secret shown once, stored sealed (`SecretBox`).
- Every terminal request is signed (HMAC-SHA256 over method, path, timestamp, nonce and body hash); a stale timestamp, a reused nonce or a wrong signature is refused.
- A sale creates a merchant checkout session (idempotent per sale reference) paid by QR, wallet or mobile money. Card requests fail closed until a `CardAcquirer` is configured.

**WhatsApp** (`whatsapp/*`)
- WhatsApp Cloud API webhook: subscription handshake with the verify token; deliveries signed `X-Hub-Signature-256` (refused without the app secret); de-duplicated by message id.
- The conversation is the USSD menu engine with channel `WHATSAPP` (same switches, risk checks and payment path; the mobile money prompt is approved on the customer's phone, so no PIN is typed into WhatsApp). Replies are recorded, and sent once the sender is configured.

**New-market rails** (`service/adaptor/mtn`, `service/adaptor/airtel`)
- MTN MoMo (Collection RequestToPay) and Airtel Money (Collections). Fail closed until configured; callbacks only trigger an authenticated status query (both are in `payport.payments.query-providers`); currencies from the register (ZMW), widened per deployment with `payport.integrations.<CODE>.currencies`. SPEC_ONLY until sandbox evidence is recorded.

**Provider settlements** (`iso20022/ProviderSettlement*`)
- Four-eyes rules recognise a rail's settlement credit on a camt.053 statement and book Dr BANK_CASH · Cr PROVIDER_RECEIVABLE[rail], traced as PROVIDER_SETTLEMENT; unmatched credits stay UNIDENTIFIED.

**Earlier in Phase 8 (V51):** QR (EMVCo, `/checkout/{id}/qr`, `/qr/resolve`), FX reference rates and quotes, USSD menus.

## 3. Frontends

| App | Phase 8 screens |
|---|---|
| Consumer web | Wallet (balances, open, top-up, send, convert, cash-out codes, statement); pay with wallet in airtime, data, bills and hosted checkout |
| Mobile | Wallet tab (same functions); pay with wallet in airtime, data, bills; EAS project id and API URL from `app.config.js` |
| Merchant portal | Agent (apply, float deposits, cash-in, cash-out, transactions, commissions); POS terminals (register, enable/disable, sales) |
| Ops console | Customer wallets, Agents & float approvals, POS & WhatsApp, Provider settlement rules, Ledger (integrity, trial balance, accounts) |

## 4. Evidence

Backend CI run 39791437666 (all jobs green): `Phase8CompletionScenarioTest` (wallets, agents, POS, WhatsApp, provider settlements over HTTP on MySQL 8, ledger integrity checked after each), `Phase8UnitTest` (MTN/Airtel fail closed and status mapping, register overrides, POS signature, WhatsApp signature, CORS), `SecurityResilienceScenarioTest` (wallet concurrency), `PlatformControlsScenarioTest` (licence gate, QR, FX, USSD). Frontend CI: consumer, mobile, ops, merchant green with the API contract check. Details: `docs/PROJECT_STATUS.md`.

## 5. Not code (owner inputs)

- Licence answers recorded in the portal (two compliance officers each).
- Credentials: WhatsApp (Meta), MTN MoMo, Airtel Money, USSD aggregator secret.
- Confirm the PROVISIONAL parameters: `WALLET.TIER_LIMITS`, `AGENT.COMMISSION_BPS`, code and quote expiries.
- Safeguarding (trust) account for wallet balances; card acquirer for POS cards; national QR scheme details.
