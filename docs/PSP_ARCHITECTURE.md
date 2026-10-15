# PayPort PSP: architecture

PayPort started as a payment gateway: it collected payments, fulfilled airtime and bills, and ran merchant checkout. This branch (`psp-foundation`) turns it into a payment service provider. The gateway stays in place as the collection and fulfilment engine. A PSP core is built around it, and that core is the financial source of truth: it controls merchants and agents, holds e-money wallets, and settles, reconciles and reports.

It is a **modular monolith**. Each PSP module lives under `zw.co.payport.gateway.psp.<module>` and owns its own tables (`psp_*`). Modules communicate in two ways:
- through a transactional outbox (an event bus with at-least-once delivery, per-handler retry and a dead-letter state);
- through narrow service interfaces.

The gateway is reached only through an anti-corruption layer (`psp.legacy.LegacyGateway` / `LegacyPayments`). Gateway code can later be replaced behind it without touching the PSP.

## How the reference architecture maps to code

| Layer | Box | Where it lives | Status |
|---|---|---|---|
| Channels | App / Web | gateway consumer app + `psp/wallet` (`/api/v1/psp/wallet/**`) | Built |
| | USSD | `psp/channel/UssdService` (Africa's Talking callback format) | Built |
| | WhatsApp | `psp/channel/WhatsAppBot` (Cloud API, signed webhook) | Built |
| | POS / mPOS | `/api/v1/psp/channels/pos/**`, HMAC-signed per terminal | Built |
| | QR | `psp/channel/EmvQr` (EMVCo merchant-presented, CRC-16) | Built |
| | Agent network | `psp/agent` (cash-in/out, float, super-agents, commissions) | Built |
| | Partner API | `/api/v1/partner/**`, HMAC + nonce + optional mTLS, scopes, idempotency | Built |
| Edge | API gateway / WAF | infrastructure, outside the app; see runbooks | Your infra |
| | OAuth2 / mTLS | JWT (staff/customer), signed requests + client-cert pinning (partners/POS) | Built |
| | HSM | `KeyProvider` interface; `EnvKeyProvider` today, HSM/KMS provider slots in | Interface built |
| Core: accounts & money | Onboarding / KYC | gateway merchant onboarding + `psp/kyc` (customer tiers 1–3) | Built |
| | Merchant accounts | `psp/merchant` (verification, risk class, products, limits, payout accounts, suspension) | Built |
| | Wallet service | `psp/wallet` (e-money, P2P, merchant pay, withdrawal, PINs) | Built |
| | Core ledger | `psp/ledger` (double-entry, append-only, hash-chained) | Built |
| Core: payments | Orchestration | gateway `TransactionOrchestratorService` + `psp/payment` lifecycle | Built |
| | Smart routing | `psp/routing` (network, health, recent failures, cost) | Built |
| | Bill payments | gateway e-Solutions/airtime adaptors, posted to the ledger | Built |
| | Fees / tax / IMTT | `psp/fees` (fee agreements, VAT on fees, IMTT, remittance) | Built |
| Core: operations | Settlement engine | `psp/settlement` | Built |
| | Reconciliation | `psp/recon` (separate from settlement; breaks never auto-correct) | Built |
| | ZiG / USD FX | `psp/fx` (rates under four-eyes, quotes, two-leg conversions) | Built |
| | Disputes / refunds | `psp/disputes` + gateway refund/dispute flows | Built |
| Core: risk & comms | Fraud engine | `psp/risk/FraudEngine` (rules, blocklists, cases) | Built |
| | AML screening | `psp/risk` (UN/OFAC/local/PEP lists, name matching, monitoring) | Built |
| | RBZ reporting | `psp/reporting` (NPS monthly, safeguarding, FIU CTR/STR, ZIMRA) | Built (file formats to confirm) |
| | Notifications | `psp/notify` (SMS, email, WhatsApp; templates; retries) | Built |
| Integration | EcoCash, OneMoney, O'mari, ZimSwitch, e-Solutions, Econet/NetOne/Telecel airtime | existing gateway adaptors | Live |
| | Telecash collections, bank APIs, ZIPIT, ZIPSS, national ID, credit bureau, RBZ FX feed, card acquirer | SPIs in `psp/integration` | See INTEGRATION_STATUS.md |
| Data | Ledger DB / audit / event bus | MySQL (`psp_*`), `psp_audit_events`, `psp_outbox_*` | Built |
| | DR | runbooks/SECURITY_AND_RESILIENCE.md | Process |

## Your five requirements and how each is met

### 1. Ledger as the financial source of truth
- **Double-entry.** Every money movement is one `JournalEntry` whose lines balance per currency. The domain refuses to build an unbalanced draft, and `LedgerService.post` checks again.
- **Append-only.** MySQL triggers block UPDATE and DELETE on entries and lines. A correction is a new reversing entry (`reverses_entry_id`, unique), approved four-eyes through `LEDGER_REVERSAL`.
- **Tamper-evident.** Entries and audit events are sealed in hash chains. A nightly integrity job recomputes the chains and checks that every account's projected balance equals the sum of its lines.
- **Balances derived from entries.** `psp_account_balances` is a projection updated in the same transaction. It is verified against the lines nightly and can be rebuilt from them.
- **Fund states.** Each merchant has separate accounts: `MERCHANT_PENDING` (T+N hold), `MERCHANT_AVAILABLE`, `MERCHANT_RESERVE` (rolling reserve), `SETTLEMENT_PAYABLE` (earmarked in a batch) and `DISPUTE_HOLD`. Provider receivables, fee revenue, tax payables (VAT, IMTT), customer refund payables, wallets, agent float and FX positions each have their own accounts too.
- **Exactly-once posting.** Each business event has one idempotency key (e.g. `COLLECT:<ref>`), enforced by a unique constraint.
- **No overdraft.** Protected accounts (merchant available, wallets, agent float) are locked in id order and cannot go negative.

### 2. Explicit payment lifecycle
- **Unique references and idempotent requests.** The 6-digit references that could collide were replaced with retry-on-duplicate. `Idempotency-Key` works on partner, POS and wallet APIs, and checkout creation is idempotent.
- **Callbacks.** Every callback is stored *before* processing in `psp_callback_inbox` and deduplicated by body hash. It is acknowledged only after it is stored. For EcoCash, OneMoney and Telecash the callback is never trusted on its own: PayPort queries the provider to confirm it. A contradiction or amount mismatch opens a reconciliation break instead of changing state.
- **A timeout is not a failure.** At the timeout a payment becomes `PAYMENT_TIMEOUT`, which means *unknown*. `UncertainPaymentResolver` keeps querying the provider with back-off (15s → 1m → 10m → 1h). It escalates to ops at 30 minutes and stops automatic polling at 72 hours. **No payment is ever marked FAILED because time passed.** If the provider never gives a definite answer, a person resolves it, and a second person must approve (`PAYMENT_RESOLVE`).
- **Fulfilment survives restarts.** The gateway's fulfilment queue lives in memory, so `FulfilmentRecoverySweeper` re-queues anything paid but not fulfilled after a restart.

### 3. Reconciliation separate from settlement
- **The settlement engine moves money.**
  - Batches go DRAFT → PENDING_APPROVAL → APPROVED (by a different person) → PROCESSING → COMPLETED / PARTIALLY_FAILED.
  - Funds are earmarked when the batch is drafted.
  - Payouts go out either as a bank file or through a payout rail.
  - An instruction is marked PAID only with a payout reference, plus proof for bank-file payouts.
  - FAILED returns the funds to the merchant's available balance. RETURNED (paid, then bounced) is posted the same way.
- **Reconciliation only finds differences.** It compares the ledger with provider callbacks and queries, bank statements (CSV import), the age of rail receivables, and the gateway's statement-recon mismatches. Every difference becomes a `ReconBreak`. A break is closed only by a recorded explanation or by a four-eyes `LEDGER_ADJUSTMENT`. **Nothing is ever corrected silently.**

### 4. Merchant and agent management
- **Merchants.**
  - Each merchant has a verification status, a risk class (which drives hold days, reserve % and reserve days), and approved products per currency (nothing is collectable unless approved).
  - Transaction limits: per transaction, daily and monthly, by count and amount.
  - Fee agreements are versioned and effective-dated; the old terms stay active until the new ones start.
  - Payout accounts are encrypted, verified four-eyes and subject to a cooling-off period; they can only be changed from the dashboard, never with an API key.
  - Suspension can be full, payouts only, or collections only.
- **Agents.**
  - Agents carry e-float in the ledger; float purchase and redemption require approval, and super-agents can transfer float.
  - Cash-in and cash-out are PIN-protected and checked by the fraud engine.
  - Commissions are banded tariffs, accrued and paid out.
  - Limits are per agent, in USD equivalent across ZiG and USD.

### 5. Security and resilience
In code:
- AES-256-GCM field encryption with key ids, so keys can rotate.
- Blind indexes, so encrypted fields remain searchable.
- Maker-checker approvals on every money-affecting change (the approver must differ from the requester, enforced in the database too).
- A hash-chained audit trail: append-only and verifiable through the API.
- Signed partner and POS requests, with nonces, timestamps, IP allow-lists and client-certificate pinning.
- SMS bodies (which contain PINs and tokens) are no longer logged.
- The operator recorded in the audit trail is the authenticated user, not a value from the request body.

Everything operational (secrets management, HSM, privileged access, DR, restore tests, incident response, PCI DSS scope) is in `runbooks/SECURITY_AND_RESILIENCE.md`.

## What changed in the existing gateway, and why

| Finding in the gateway | Risk | Change |
|---|---|---|
| No ledger; balances computed from transaction rows | No financial source of truth; nothing to reconcile against | PSP ledger; every collection, fee, refund and payout is posted |
| EcoCash reconciler marked payments FAILED after 30 min | Customer charged, merchant told it failed, refunds missed | Give-up branch removed; uncertain-payment resolver |
| Timeout watchdog changed status directly | Bypassed the audited state machine | Goes through `markPaymentOutcomeUnknown` (audited transition) |
| EcoCash/OneMoney callbacks trusted without verification; OneMoney amount read as 0 | Forged callback = free goods/credit | Inbox + verify-by-query + amount check |
| Callback errors swallowed | Lost payment confirmations | Stored before processing; retried; reprocess API |
| 6-digit references | Collisions as volume grows | Retry on duplicate in checkout |
| Operator name taken from the request body in ops actions | Spoofable audit trail | Taken from the authenticated principal |
| Settlement with no approval or proof | Single person can pay out; no evidence | Settlement engine (four-eyes, proof); old run disabled by flag |
| Payout details in plain text | Data breach exposes bank details | Encrypted PSP payout accounts |
| `ddl-auto=update` with Flyway | Schema drifts silently | Configurable; move to `validate` (see rollout plan) |
| SMS bodies logged at INFO | PINs/tokens in logs | Debug only, never for PIN reset |
| In-memory fulfilment queue | Paid-but-unfulfilled after restart | Recovery sweeper |
| `/api/v1/transactions/*` public with guessable references | Anyone can enumerate transaction status | **Flagged, not changed**: the consumer app depends on it. Add a per-transaction access token or require auth before go-live |

## Who can do what

| Role | Source | Can |
|---|---|---|
| `OPS` | `users.role` | Payments desk, callbacks, recon breaks, merchant controls, channels |
| `FINANCE` | `users.role` | Settlement, fees/tax, ledger, recon, reports |
| `COMPLIANCE` | `users.role` (**new value**; set it on compliance officers' user rows) | Risk cases, AML, sanctions lists, merchant verification, reports |
| `ADMIN` | `users.role` | All of the above |
| `CUSTOMER` | `users.role` (consumer app login) | Wallet API |
| `PARTNER` + `SCOPE_*` | `psp_partner_clients` (signed requests) | Partner API |
| `TERMINAL` | `psp_terminals` (signed requests) | POS API |
| Merchant | gateway merchant API key / dashboard | `/api/v1/merchant/psp/**` |

Four-eyes approvals are listed and actioned at `/api/v1/psp/admin/platform/approvals`. The approver must hold the required role and must not be the requester.

## API map

- **Staff** (`/api/v1/psp/admin/...`):
  - `platform`: approvals, audit, integrity, events;
  - `ledger`;
  - `payments`: callbacks, unresolved payments, routing;
  - `settlement`;
  - `recon`;
  - `merchants/{id}/...`, `fees`, `tax`;
  - `customers`, `agents`, `fx`, `wallet-tariffs`;
  - `risk`;
  - `reports`, `refunds`, `disputes`;
  - `channels`.
- **Merchant**: `/api/v1/merchant/psp/` for balances, statement, holds, settlements, payout accounts, fees, disputes, refunds, QR and terminals.
- **Customer wallet**: `/api/v1/psp/wallet/` for register, send, pay merchant, pay by QR, FX quote and convert, PIN, KYC and transactions.
- **Partner**: `/api/v1/partner/` for payments, balance and statement.
- **Channels**:
  - `/api/v1/psp/channels/ussd/at`;
  - `/api/v1/psp/channels/whatsapp/webhook`;
  - `/api/v1/psp/channels/pos/` for payments, QR and heartbeat.

## Code generation

The PSP entities and their tables are generated from the same `tools/codegen/*.spec` files:
- `gen_entities.py` writes the JPA classes;
- `gen_ddl.py` writes the Flyway `CREATE TABLE`s (V34 to V39).

To add a field, edit the spec, regenerate both, and add a new migration (never edit an applied one).
