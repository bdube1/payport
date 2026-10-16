# Phase 8: additional products, each behind its licensing question

_Branch `phase2-payment-lifecycle`, 2026-10-16. Roadmap: "Wallets, agents, QR (via the National Switch scheme), USSD, WhatsApp, POS, FX conversions, **each only after its licensing question is answered**. Harvest the parked branch code."_

## 1. The licence gate

`service_lines` (V51) holds one row per product line with its licensing question, licence status (NOT_ASSESSED, APPLIED, GRANTED, NOT_REQUIRED, REFUSED), reference and regulator.

- A licence decision is a request by one COMPLIANCE officer that a second approves; GRANTED or APPLIED needs a licence or application reference.
- A line (and any channel gated on it) can be switched on only when the status is GRANTED or NOT_REQUIRED — enforced in code and by a database CHECK.
- Withdrawing a licence (REFUSED) switches the line and its channels off at once.
- Lines whose code is still parked cannot be switched on at all.

| Line | Status of the build | Licensing question (owner / compliance to answer) |
|---|---|---|
| QR payments | **Built** | RBZ authorisation for QR acceptance; joining the national QR scheme |
| FX quotes | **Built (quotes)**; executing conversions parked | Exchange Control Act / authorised dealer rules; reference rate source |
| USSD | **Built** (channel; not licence-gated, switchable) | Aggregator contract (short code) |
| WhatsApp | Parked (channel and line off) | Consumer and data protection rules; Meta BSP contract |
| Wallets | Parked | E-money licence; trust account |
| Agents | Parked | Agent network authorisation; float rules |
| POS acquiring | Parked | Acquiring licence or sponsor acquirer; PCI DSS |

Everything defaults to off. No licensing question has been answered yet, so no Phase 8 line can carry live traffic today.

## 2. What was built

**QR (EMVCo merchant-presented, harvested from `psp-foundation`'s `EmvQr`)**
- `GET /api/v1/checkout/{id}/qr`: a dynamic QR for an open checkout session. Country (tag 58) and ISO 4217 numeric currency (tag 53) come from the market settings; CRC-16/CCITT-FALSE.
- `POST /api/v1/qr/resolve`: a scanned PayPort QR back to the session, checking that amount and currency still match (an altered amount is refused: QR_MISMATCH; a broken CRC: INVALID_QR).
- Works only while the QR line and channel are on.

**FX (design harvested from `FxRateService`)**
- Reference rates with maker-checker (database CHECK: approver ≠ proposer; ACTIVE needs an approver).
- Quotes: amount × rate less the spread, rounded down to the target currency's minor unit, valid 60 s; refused unless the FX line is on.
- Executing conversions (two-leg postings through FX_CLEARING) stays parked until the licence answer.

**USSD (menus rebuilt on the live payment path)**
- `POST /api/v1/ussd` (Africa's Talking style, `CON`/`END`), signed by the aggregator (`X-PayPort-Ussd-Signature`, HMAC-SHA256).
- Buy airtime, pay a bill (with the biller lookup: holder name, payable), check a payment (only the payer's own).
- Same orchestrator, risk engine, idempotency and outcomes as every channel; one payment per USSD session even if the aggregator retries.
- The channel can be switched off from the portal.

## 3. Evidence

`PlatformControlsScenarioTest.phase8_licenceGatedLines_qr_fx` and `.ussdMenus_signed_channelSwitch_andStatusOnlyForThePayer`; `EmvQrTest` (CRC check value, round trip, tamper detection). Results: `docs/PROJECT_STATUS.md`.

## 4. Open

- Answers to the licensing questions (table above).
- National QR scheme details (template id / GUID) from ZimSwitch / RBZ, so other wallets can pay PayPort codes.
- USSD aggregator contract and shared secret (`payport.ussd.secret`; without it every USSD request is refused).
- Wallets, agents, WhatsApp and POS: harvest after their licence answers; the code on `psp-foundation` predates the current ledger and approval model and must be rebuilt on it, not copied.
