# Phase 0: Capability and verification register

**Status values:**
- Verified functional
- Functional with limitations
- Partially implemented
- Implemented but not verified
- Stub or simulation
- Missing
- Defective or insecure

**Evidence values:** [Tested] executed here; [Observed] read in code or config; [User] you report it works in production; [Not verified].

**Nothing is "Verified functional"** at the end of Phase 0. The application could not be built or run here (see report 05). Gateway features you run in production are recorded as *Functional with limitations [User]*: production use is real evidence, but I have not observed it myself.

## A. Existing gateway (`payport-gateway`, as uploaded)

| # | Capability | Status | Evidence and notes |
|---|---|---|---|
| G1 | Consumer airtime/data purchase (Econet, NetOne, Telecel) | Functional with limitations | [User] [Observed] Fulfilment adaptors exist. The fulfilment queue is in-memory, so jobs are lost on restart. |
| G2 | Bill payments via e-Solutions (ZESA tokens, Bulawayo City Council, ZOL, Nyaradzo, Moonlight) | Functional with limitations | [User] [Observed] Bespoke adaptor per biller, with no shared biller framework. |
| G3 | Wallet collections: EcoCash, OneMoney, O'mari | Functional with limitations | [User] [Observed] EcoCash and OneMoney callbacks are not authenticated. OneMoney callback amount is read as 0. The EcoCash reconciler marks payments FAILED after 30 min. |
| G4 | Card / ZimSwitch collection (hosted widget) | Functional with limitations | [User] [Observed] Status is confirmed by query (`queryStatus`), which is the correct pattern. |
| G5 | Telecash collection | Missing | [Observed] No payment adaptor (Telecel airtime only). |
| G6 | InnBucks collection | Missing | [Observed] Statement parser only; no payment adaptor. |
| G7 | Transaction state machine | Functional with limitations | [Observed] One `TransactionStatus` field mixes payment and fulfilment states (PAYMENT_*, FULFILMENT_*, REFUND_*). The watchdog bypassed transitions (fixed on the branch, not verified). |
| G8 | Merchant onboarding + KYB documents | **Defective or insecure** | [Observed] Public `/merchant-portal/track/{reference}` endpoints use references `MER-yyyyMMdd-NNNN` (9,000 per day, enumerable). Without authentication anyone can read status and review notes, **upload documents to another applicant's file** and resolve review notes. |
| G9 | Merchant API keys (TEST/LIVE) | Functional with limitations | [Observed] Secrets stored as SHA-256 hash (acceptable for high-entropy keys). No scopes. |
| G10 | Merchant checkout sessions (API) | Functional with limitations | [User] [Observed] Reference collisions possible (fixed on the branch, not verified). |
| G11 | **Payment links and dashboard-created checkouts** | **Defective or insecure (critical)** | [Observed] No API key means `isTestMode = true`, so the payment is simulated as SUCCESS, the session is marked PAID, the merchant webhook fires, and it becomes settleable. Not reproduced at runtime. |
| G12 | Merchant webhooks | Functional with limitations | [Observed] HMAC-signed, retries with back-off. Allows `http://`. **No SSRF guard**: a merchant can point it at internal addresses. Secret stored in plaintext. |
| G13 | Refunds | Partially implemented | [Observed] Records refunds against sessions; no accounting effect. Payout of the refund to the customer is manual. |
| G14 | Disputes | Partially implemented | [Observed] Case records only; no fund hold. |
| G15 | Merchant settlement (legacy) | **Defective or insecure** | [Observed] No maker-checker, no proof of payment, money pro-rating uses `double`, and it **does not exclude simulated test payments**. |
| G16 | Statement reconciliation (EcoCash, OneMoney, NetOne, Telecel, InnBucks, O'mari parsers) | Functional with limitations | [User?] [Observed] Matches provider statements against transactions. No ledger to reconcile against. |
| G17 | Commission and consolidated reports | Functional with limitations | [Observed] Computed from transaction rows, not a ledger. |
| G18 | Staff auth (JWT), roles OPS/ADMIN/FINANCE | Functional with limitations | [Observed] Operator identity was taken from the request body in ops actions (fixed on the branch, not verified). |
| G19 | Rate limiting | Partially implemented | [Observed] In-memory, per-IP, per-instance. |
| G20 | Public transaction lookup `/api/v1/transactions/*` | **Defective or insecure** | [Observed] Unauthenticated, with guessable references. |
| G21 | SMS (Africa's Talking), email (SMTP) | Functional with limitations | [Observed] SMS bodies, including PINs and tokens, were logged at INFO (fixed on the branch, not verified). |
| G22 | AI assistant (Gemini) | Functional with limitations | [Observed] Not in PSP scope. |
| G23 | Provider health checks | Partially implemented | [Observed] Health scheduler exists; no circuit breaker. |
| G24 | Automated tests | Missing (effectively) | [Observed] 3 unit test classes for 26k lines. |
| G25 | Container deployment | **Defective** | [Observed] The Dockerfile references a non-existent module path. |
| G26 | Schema management | Functional with limitations | [Tested] V1–V31 apply cleanly. `ddl-auto=update` lets Hibernate drift the schema. |

## B. PSP layer (`psp-foundation` branch, built in this project)

**Headline:** about 19,000 lines across 225 files, compile-checked only against stubs, never executed. **Every item below is at most "Implemented but not verified".** Several items also conflict with the requirements you have now given (multi-country, the RBZ QR guidelines, phase discipline).

| # | Capability | Status | Evidence and notes |
|---|---|---|---|
| P1 | Double-entry ledger (accounts, entries, lines, balances, holds) | Implemented but not verified | [Tested: DB] Schema, idempotency key, append-only triggers and positive amounts proven on real MySQL. [Tested: unit] Posting rules balance in 18 unit cases. [Not verified] Concurrency locking, rollback behaviour, Hibernate mapping. **Defects:** append-only defeated by TRUNCATE or DROP TRIGGER with the app's privileges; DB does not enforce balance; currency hard-coded to USD/ZWG. |
| P2 | Hash-chained audit and ledger seals, integrity job | Implemented but not verified | [Observed] |
| P3 | Transactional outbox, idempotency keys, maker-checker approvals | Implemented but not verified | [Tested: DB] The four-eyes constraint works. |
| P4 | Field encryption (AES-GCM, key ids, blind index) | Implemented but not verified | [Observed] Env-var keys; HSM/KMS provider not implemented. |
| P5 | Callback inbox + verify-by-query + uncertain-payment resolver | Implemented but not verified | [Tested: unit] Back-off policy. [Not verified] Provider query responses: the adaptors' status mapping was never exercised against a sandbox. |
| P6 | Ledger posting of collections, fees, VAT, IMTT | Implemented but not verified | [Observed] Tax rates intentionally unseeded. IMTT rule (2% USD / 1.5% ZiG since 1 Jan 2026) not encoded; must be configured. |
| P7 | Merchant controls (verification, risk class, products, limits, suspension, payout accounts) | Implemented but not verified | [Observed] **Wired into the live checkout path immediately.** Enforcement is on by default, so behaviour changes on deploy, contradicting the "shadow" rollout. |
| P8 | Smart routing | Implemented but not verified | [Observed] Also wired into live checkout. Network-prefix logic is Zimbabwe-specific. |
| P9 | Settlement engine (batches, four-eyes, bank file, proof, failure and return) | Implemented but not verified | [Observed] Bank-file rail only; no live payout rail. |
| P10 | Reconciliation breaks, bank CSV import, receivable ageing | Implemented but not verified | [Observed] **Duplicates** the gateway's statement reconciliation rather than extending it. |
| P11 | Fee agreements, tax rates, tax remittance | Implemented but not verified | [Observed] |
| P12 | Customer KYC tiers, e-money wallets, PINs | Implemented but not verified | [Observed] **Needs an e-money/mobile money licence** that PayPort may not hold. Belongs to a later phase. |
| P13 | Agent network (float, cash-in/out, commissions) | Implemented but not verified | [Observed] Needs RBZ agency approval. Later phase. |
| P14 | ZiG/USD FX | Implemented but not verified | [Observed] Two-currency assumption. Exchange-control treatment not established. |
| P15 | Fraud rules, blocklists, cases | Implemented but not verified | [Observed] **Live-blocking from the first deploy** with untuned placeholder thresholds. |
| P16 | Sanctions/PEP screening, AML monitoring, STR/CTR reports | Implemented but not verified | [Observed] Name matching was never evaluated against real list data. FIU file formats were not confirmed. |
| P17 | Disputes and refunds with ledger holds | Implemented but not verified | [Observed] **Duplicates** the gateway's dispute and refund records. Two sources of truth. |
| P18 | Regulatory reports (RBZ NPS, safeguarding, FIU, ZIMRA) | Stub or simulation | [Observed] Report layouts were designed without the official templates. |
| P19 | Notifications (SMS, email, WhatsApp templates) | Implemented but not verified | [Observed] |
| P20 | Partner API (HMAC, nonce, mTLS pinning, scopes) | Implemented but not verified | [Tested: unit] Signature maths. |
| P21 | EMV QR | **Defective (regulatory)** | [Observed] Generates PayPort-specific merchant-presented codes. The RBZ QR Guidelines (effective 11 March 2026) require a scheme Merchant Account Information ID obtained via the National Switch and **end closed-loop QR**. Not usable as built. |
| P22 | USSD, WhatsApp channels | Implemented but not verified | [Observed] Short code, Meta verification and aggregator contracts not in place. Later phase. |
| P23 | POS terminal API | Implemented but not verified | [Observed] No terminal hardware or certification. |
| P24 | Payout rails, ID verifier, credit bureau | Stub or simulation | [Observed] Manual and sandbox adapters only. |
| P25 | Cutover of legacy unsettled balances | Implemented but not verified | [Observed] |
| P26 | Country / legal-entity configuration | **Missing** | [Observed] Zimbabwe assumptions hard-coded in 74 places (currencies, MSISDN prefixes, "HARARE", IMTT). |
| P27 | Canonical payment model, ISO 20022 | **Missing** | |
| P28 | Merchant webhooks for PSP events (settlement paid, dispute, payout) | **Missing** | Gateway webhooks cover checkout status only. |
| P29 | Merchant portal and admin UI for PSP functions | **Missing** | APIs only; frontends not available to inspect. |
| P30 | Developer docs, SDKs, sandbox | **Missing** | |
| P31 | Distributed job locking, multi-instance safety | **Missing** | |
| P32 | Integration, concurrency and failure tests | **Missing** | |

## C. What this means

The gateway is a **real, working collection and fulfilment product** with **specific, fixable security and financial defects** (G8, G11, G12, G15, G20). Its provider adaptors are its most valuable asset.

The PSP branch contains **useful designs and a database schema that has now been proven to migrate**. It is **not** a verified foundation. It also went beyond what should be built before the ledger is verified: wallets, agents, USSD, WhatsApp, FX and AML. It wires untested controls into the live checkout path, and it is Zimbabwe-specific where your platform needs to be multi-country.
