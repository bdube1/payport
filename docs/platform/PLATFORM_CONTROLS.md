# Operating PayPort from the portal (no code changes)

_2026-10-16. Migrations V50–V52. Ops console: **Markets & Currencies**, **Channels & Product Lines**, **Products & Payment Methods**, **Customer Wallets**, **Agents & Float**, **POS & WhatsApp**, **Provider Settlement Rules**, **Ledger**. Step-by-step switch-on for every product and credential: [`docs/runbooks/SWITCH_ON.md`](../runbooks/SWITCH_ON.md)._

The rule for every switch: **turning money OFF is immediate** (one person, reason required, audited); **turning money ON needs a second person**, and a COMPLIANCE officer when it enables money. Nobody approves their own request (database CHECK). Every request, decision and switch is in the hash-chained compliance audit log.

| What | Where | Off | On |
|---|---|---|---|
| A market (country) | Markets & Currencies | Suspend: immediate | Request status LIVE → readiness blockers must be clear → COMPLIANCE approves |
| A currency (accept at all) | Markets & Currencies | Disable: immediate | Request → COMPLIANCE approves |
| Live money in a currency | Markets & Currencies | Stop live money: immediate | Request → COMPLIANCE approves |
| Bank account, BIC, payout format, statement guideline, FIU format, legal name | Markets & Currencies | — | Request → a second person (COMPLIANCE if it changes where money goes) |
| A new country | Markets & Currencies → Add a market | — | Starts in SANDBOX; another ADMIN approves the creation; going LIVE as above |
| A channel (web, mobile, USSD, merchant API, payment links, hosted checkout, QR, WhatsApp, POS, agent) | Channels & Product Lines | Switch off: immediate, refuses new payments with 503 CHANNEL_UNAVAILABLE ("No money was taken") | Switch on (ADMIN/OPS); licence-gated channels only after the licence decision |
| A product line (Phase 8) | Channels & Product Lines | Immediate | ADMIN, only when the licence status is GRANTED or NOT_REQUIRED (two compliance officers; database CHECK) |
| A product (e.g. Econet airtime, ZESA) and its currencies | Products & Payment Methods | Immediate | Immediate (audited) |
| A payment method (EcoCash, OneMoney…) | Products & Payment Methods | Immediate | Immediate (audited) |
| A provider (emergency) | Integrations → Suspend | Immediate kill switch | Resume (audited) |

## Switching Zambia on

1. Markets & Currencies → Zambia shows **Before going live**: the blockers (placeholder legal name, placeholder ZMW bank account, required screening list not loaded…).
2. An administrator requests: status LIVE, the legal entity's registered name, the ZMW settlement account, and ZMW **live**.
3. A compliance officer approves. In that transaction PayPort enables ZM and ZMW in the reference data (through the database's definer procedures; the application user still cannot write reference data directly), renames the entity, and reloads the market settings on every instance.
4. Zambian mobile numbers (+260, the market's numbering plan) are accepted, merchants can be enabled for live ZMW, payouts use the market's payout format and bank account.

Proven by `PlatformControlsScenarioTest.zambiaGoesLiveFromThePortal_…` — including that the request is refused while blockers remain, that the requester and another ADMIN cannot approve it, and that a suspension is immediate.

**Providers for Zambia:** MTN MoMo and Airtel Money adapters are built (V52; payment methods `MTN_MOMO`, `AIRTEL_MONEY`, inactive). They fail closed until their credentials are configured (see the switch-on runbook), are confirmed by status query before any callback changes a payment, and declare ZMW; another currency is added with `payport.integrations.<CODE>.currencies`. Zambian billers and airtime vendors are still adapters to add when contracted.

## Screening is never "clear" unless it is

- An ad-hoc check returns the full result (`outcome` CLEAR / POTENTIAL_MATCH / INCOMPLETE), never a bare empty match list.
- If no required list is active, or a list a market requires is not an active required list, screening is INCOMPLETE.
- A rule or screening failure during a payment is handled like INCOMPLETE (alert; blocks only under an approved enforcing rule).
- KYB: "sanctions screened" is no longer a tick-box. The server screens at recommendation and again at decision; the recorded checklist item comes from the CLEAR run (with its run id). A rejection never records "screened".
