# Phase 0: Proposed target architecture

**Status: proposal for your approval.** Decisions are recorded as ADRs in `docs/adr/`.

## 1. Shape: an evolving modular monolith

**Recommendation:** keep a single Spring Boot deployable built from the existing gateway (ADR-0001). The gateway code is retained and progressively re-homed into modules. Its provider adaptors are the most valuable asset.

**Why not microservices now:**
- One team, one database and strong consistency needs on money. Distributed transactions would add failure modes such as lost and duplicated postings without any present benefit.
- Module boundaries are enforced in code instead: package-private internals, an ArchUnit test that forbids cross-module repository access, and each module owning its tables. Any module can later be extracted along those seams.

## 2. Modules and ownership

| Layer | Module | Owns (tables / rules) | Exposes |
|---|---|---|---|
| Channels | `checkout`, `paylinks`, `merchant-api`, `partner-api`, later `qr`, `ussd`, `pos`, `agent-ui` | Request validation, presentation only | Calls `payments` and `merchants` only; **no channel touches the ledger** |
| Edge | `identity` | Staff, merchant users, API credentials (scoped, rotatable, test/live), partner clients, sessions | AuthN/AuthZ, tenant context |
| Core | `countries` | Country profiles, legal entities, currencies, calendars, versioned policy sets | Policy lookup by (country, entity, version) |
| Core | `merchants` | Onboarding, KYB cases, verification per country, approved products, limits, agreements, settlement destinations, suspension | Eligibility checks |
| Core | `payments` | Canonical payment, attempts, state machine, idempotency, callback inbox, uncertain-outcome resolution | Payment commands and queries; events |
| Core | `orchestration` | Routing policy, provider selection, circuit breakers | Route decisions with reasons |
| Core | `providers` | Adapter registry, capability declarations, simulators, provider health | Adapter SPI |
| Core | `billing` | Fee and commission schedules, taxes and levies (effective-dated, approved) | Fee quotes; posting inputs |
| Core | `ledger` | Chart of accounts, journals, balances, holds, periods, integrity | `post(template, inputs)`, balances, statements |
| Core | `fulfilment` | Biller/airtime catalogue, validation, delivery jobs, receipts | Delivery outcome events |
| Ops | `settlement` | Eligible-funds calculation, schedules, batches, approvals, payout instructions | Payout commands to `providers` |
| Ops | `reconciliation` | Source imports, matching rules, exceptions, evidence | Exceptions; approved adjustment requests to `ledger` |
| Ops | `disputes` | Refunds, reversals, returns, chargebacks | Ledger postings; provider refund commands |
| Ops | `fx` | Rates (source, timestamp), quotes, conversions | Conversion postings |
| Risk | `risk`, `compliance` | Rules, screening, cases, reports | Decisions (allow/review/block) with reasons |
| Comms | `notifications`, `webhooks` | Templates, deliveries, merchant webhook endpoints | — |
| Platform | `platform` | Outbox/event bus, jobs (leased, idempotent), approvals (maker-checker), audit chain, crypto/key provider | — |

**Single owner per concept (ADR-0003).** For example, refunds are owned by `disputes`. The gateway's refund table becomes either a read model or is migrated. The same applies to settlement, reconciliation and disputes.

## 3. Payment lifecycle: separate the state machines

These replace the single `TransactionStatus`.

- **Payment:**
  `CREATED → REQUIRES_ACTION → PROCESSING → (SUCCEEDED | FAILED | CANCELED | UNKNOWN)`.
  `UNKNOWN` is resolved only by enquiry or investigation, never by a timer.
- **Attempt** (per provider request):
  `SENT → ACCEPTED | DECLINED | UNKNOWN`.
  It records the request id, provider reference, raw response hash and timings.
  **A new attempt is allowed only when the previous attempt is provably not successful.**
- **Posting:** whether the ledger effect for a business event exists. This is derived from the idempotency key, not stored as a status.
- **Fulfilment** (bills/airtime):
  `PENDING → DELIVERING → DELIVERED | FAILED | UNKNOWN`, with the same no-blind-retry rule.
- **Settlement:** owned by `settlement`. The link from payment to payout is via ledger entries.

**Live mode vs test mode** is a first-class attribute of every merchant credential, payment and ledger book. Test-mode activity posts to a separate test ledger and can never be settled (fixes R1).

## 4. Ledger (summary; detail in Phase 1 design)

- Integer minor units. Currency precision comes from the currency table.
- Rounding is defined per posting template, using banker's rounding only where the agreement says so.
- **Chart of accounts per legal entity:**
  - merchant liabilities: pending, available, reserve, in-settlement, dispute-hold;
  - provider receivables and payables;
  - fee revenue; tax payables;
  - suspense;
  - FX clearing and FX gain/loss;
  - opening-balance equity;
  - safeguarding (trust) cash.
- **Posting templates** are versioned. Every entry records the template id and version, plus the fee, tax and policy versions used.
- **Invariants:**
  - per entry and currency, debits equal credits;
  - protected accounts never go negative;
  - one effect per idempotency key;
  - the balance snapshot equals the sum of lines;
  - the safeguarding cash balance is at least the customer and merchant liabilities.

  These are checked in the posting transaction **and** continuously by a detector job.
- **Period close** per entity: closed periods accept only adjustments dated into an open period.
- **Reuse:** the branch's ledger code is the starting point. It must be made multi-entity and multi-currency, run on least-privilege DB access, and proven by integration and concurrency tests.

## 5. Multi-country and multi-currency model

- **Reference data:**
  - `countries` (ISO 3166);
  - `currencies` (ISO 4217, minor units);
  - `legal_entities`: the Payport entity, or partner, licensed in a country;
  - `country_products`: payment methods allowed per country;
  - `calendars` (business days, cut-offs, time zone).
- **Policy sets.** Versioned, approved bundles per (country, entity): limits, KYB requirements, settlement cycles, reserves, tax/levy rules, retention, data-hosting constraints. Every payment and posting records the policy-set version it used.
- **Policy code.** Where configuration is not enough (a tax that depends on currency and instrument, such as Zimbabwe's IMTT), the rule lives in a `CountryPolicy` implementation selected by country code. No `if (country == "ZW")` anywhere else.
- **A merchant has:**
  - a registered country;
  - **market enablements**: (country, entity, verification status, products, limits, agreement), each approved separately;
  - **settlement destinations**: (country, currency, rail, account), verified and cooled-off.
- **A payment records separately:** transaction country, transaction currency, settlement currency, reporting currency and destination country.
- **FX:**
  - only approved currency pairs;
  - every conversion stores rate, source, timestamp, spread and fee;
  - conversions post through FX clearing, with gain/loss recognised per entity policy;
  - no netting across currencies.

## 6. ISO 20022 approach (ADR-0005)

- **The canonical internal model comes first.** It is designed from the ISO 20022 business components the supported flows need: parties, agents, accounts, amounts, dates, remittance, purpose, status reasons and identifiers. It is still Payport's own model, so mobile-money and biller flows that have no ISO counterpart are first-class.
- **Mapping layer.** One module translates the canonical model to and from specific message versions (e.g. `pain.001`, `pacs.008`, `pacs.002`, `pacs.004`, `camt.053`/`camt.054`). Each mapping comes from a verified implementation guide (ZIPSS, a sponsor bank, PAPSS, a partner). Transport (files, MQ, API, SWIFT) is a separate concern.
- **Message store.** Original messages are kept (hashed, access-controlled) with full correlation:
  instruction → canonical payment → message id → provider reference → journal entries → settlement → outcome.
- **No message version is chosen yet.** I could not confirm which ISO 20022 versions ZIPSS or your prospective sponsor bank use. The first realistic use is probably **`camt.053` bank statements for reconciliation** and **payout files to a sponsor bank**, not scheme messaging. The matrix will be filled when specifications are in hand.

## 7. Unified merchant experience across markets

1. **One merchant account.** It has one or more market enablements. Each enablement is tied to the legal entity (Payport's or a partner's) that contracts in that market.
2. **One integration.** The same API, keys and hosted checkout everywhere. Payment-method availability is computed per request from: merchant enablement × customer country × currency × provider availability.
3. **Consolidated view.** The dashboard aggregates across enablements. Every figure stays tagged with entity, currency and country, and totals are never summed across currencies without an explicit FX view.
4. **Settlement.** Per (enablement, currency), to a verified destination in a permitted country, on that market's cycle.
   - **Cross-border payout**, for example ZiG collections settled into a Kenyan account, is **not** a configuration switch. It needs a lawful route: an exchange-control approval, a cross-border licence holder (an ADLA in Zimbabwe), or a bank. The architecture models it as a **cross-border settlement route**: a separate, approved capability with its own FX and compliance steps.
5. **Reconciliation** is per entity and per account, with a consolidated status view.

## 8. Automation: highest-value opportunities, in order

| # | Automation | Operational benefit | Depends on |
|---|---|---|---|
| A1 | **Uncertain-payment resolution** (status enquiry with back-off, escalation pack with evidence) | Removes the largest manual queue: "did this payment go through?" | Phase 2 attempts model; provider status APIs |
| A2 | **Provider statement and bank statement auto-import + matching** with categorised exceptions | Replaces spreadsheet reconciliation | Phase 3; statement specs |
| A3 | **Eligible-funds calculation and settlement batch drafting**, with approval only above thresholds | Settlement becomes a review task, not a calculation | Phase 1 ledger, Phase 3 |
| A4 | **Durable fulfilment jobs** with safe resume | Ends paid-but-not-delivered after restarts | Phase 2 job framework |
| A5 | **Payout status tracking + return handling** | Finance stops chasing banks for confirmations | Payout rail with status API or statement feed |
| A6 | **Provider health and circuit breaking**, with routing that never re-routes a possibly-successful attempt | Fewer failed payments during outages, no duplicates | Phase 5 |
| A7 | **Webhook delivery with replay console** | Merchants self-serve; fewer support tickets | Phase 4 |
| A8 | **Daily integrity and safeguarding checks** with actionable alerts | Early detection of financial errors | Phase 1 |
| A9 | Rule-based monitoring and screening queues | Compliance works cases, not raw data | Phase 6 |

**Job framework (platform):** durable job rows, DB lease or claim (`SKIP LOCKED`), idempotency key per job unit, bounded retries, a dead-letter state, run history, and replay through an approved command. This is required before running two instances.

## 9. Security architecture highlights

- **Tenant isolation.** Every merchant-scoped query takes the merchant id from the authenticated principal, never from the request. This is enforced by a repository-level guard and covered by tests.
- **Credentials.** Scoped API keys (test/live, per permission), rotation with overlap, revocation, last-used tracking. OAuth2 client credentials for partners; mTLS optional.
- **Secrets.** In a secrets manager; field encryption through a KMS/HSM-backed key provider.
- **Least privilege.** On the database (separate migration and app users) and for staff (role + approval matrix).
- **Webhooks.** Egress through an allow-listed proxy, with private IP ranges blocked.
- **Card data.** Only through hosted fields, to keep PCI scope minimal until a deliberate decision is made otherwise.
