# Phase 0: Prioritised roadmap, dependencies and acceptance criteria

The phases follow §15 of your prompt, with one evidence-driven change. A short **Phase 0.5 (containment and a real build)** comes before Phase 1, because:
- three critical defects affect the running gateway today (R1–R3);
- nothing can be verified until the project builds with its real dependencies.

**How the `psp-foundation` branch is used:**
- It is **not merged or deployed as a unit.**
- Each phase **harvests** the relevant parts (ledger, outbox, approvals, inbox, resolver, settlement), reworks them to the target architecture, and proves them with that phase's tests.
- Wallets, agents, USSD, WhatsApp, POS, QR and FX stay **parked** on the branch until Phase 8 and the operating-model decision.

**Acceptance evidence for every phase:**
- a green CI run (real `mvn verify`);
- migrations applied to a clean DB and to a production-schema copy;
- the application started;
- tests listed per phase;
- persisted records inspected;
- an updated `PROJECT_STATUS.md`.

---

## Phase 0.5: Containment and verified baseline (≈1–2 weeks)

**Scope**
1. Fix R1:
   - add an explicit `livemode` on sessions, derived from the creating credential or merchant mode;
   - make payment links and dashboard sessions live for live merchants;
   - exclude test-mode payments from settlement;
   - add a data check script for past exposure.
2. Fix R2: tracking token or applicant login for public onboarding endpoints.
3. Fix R3: minimal response plus a per-transaction access token on public lookups (needs a coordinated frontend change).
4. CI pipeline with a real Maven build, the gateway's existing tests, and Testcontainers MySQL.
5. Production schema dump diffed against V1–V31 (D1).
6. Restore drill of a production backup into staging.

**Dependencies**
- Maven access (or your CI).
- Production schema dump.
- The frontend repositories (for R1/R3 UI impact).

**Acceptance tests**
- Payment link in live mode calls the provider simulator; never `TEST_SIM_`.
- A test-mode session is never selected by settlement.
- Unauthenticated access to `/merchant-portal/track/{ref}` without a valid token is rejected.
- Transaction lookup without a token returns 404 or a minimal body.
- CI green on the gateway.
- Restore drill timed and recorded.

## Phase 1: Financial foundation (≈4–6 weeks)

**Scope**
- Upgrade to a supported Spring Boot line.
- Platform: least-privilege DB users; migration preconditions; durable job framework with leases; outbox; approvals; audit chain with external anchoring; secrets manager integration.
- `countries` / `currencies` / `legal_entities` reference data (minimal, ZW + USD/ZWG enabled).
- Ledger:
  - multi-entity chart of accounts;
  - versioned posting templates;
  - balance invariants (ADR-0004);
  - period control;
  - continuous integrity detector.
- **Shadow posting** of gateway events: collections, fees, refunds and settlements post to the ledger **without changing any live behaviour** (feature flag, observe-only).
- Fee/tax configuration, effective-dated and approved.

**Dependencies:** Phase 0.5; chart-of-accounts sign-off by your finance lead; tax adviser input on IMTT/VAT.

**Acceptance tests**
- For a scripted set of 50+ scenarios, every journal balances per currency, and balances equal the sum of lines. Scenarios: payment, fee, VAT, partial refund, full refund, reversal, approved adjustment, opening balance.
- Concurrency test: 200 parallel debits against one available balance never overdraw and never double-post.
- Kill-and-restart during posting leaves no partial entry.
- App DB user cannot TRUNCATE, ALTER or DROP TRIGGER.
- Shadow ledger vs legacy merchant figures agree for a full week of production-copy data, or every difference is explained.

## Phase 2: Payment lifecycle (≈4–6 weeks)

**Scope**
- Canonical payment + attempts model; separated state machines; idempotency everywhere.
- Callback inbox with verification per provider.
- Uncertain-outcome resolver.
- Durable fulfilment jobs.
- Provider simulators for EcoCash, OneMoney, O'mari, ZimSwitch and one biller.

**Dependencies:** provider sandbox credentials (optional but strongly preferred); Phase 1.

**Acceptance tests**, run against the simulators:
- duplicate callbacks;
- out-of-order callbacks;
- callback before the API response;
- timeout followed by late success;
- timeout followed by definite failure;
- provider down;
- concurrent identical requests with the same idempotency key.

In every case: **one** financial effect, correct final state, no blind retry.

## Phase 3: Reconciliation and settlement (≈4–6 weeks)

**Scope**
- One reconciliation module, merging the gateway's statement parsers with ledger-side matching, exceptions and evidence.
- Eligible-funds calculation; settlement batches with approval thresholds; payout adapter for the sponsor bank's format.
- Payout status, return handling, payout idempotency.
- Retirement of legacy settlement after a parallel-run period.

**Dependencies:** sponsor bank file or API specification; sample statements from each provider.

**Acceptance tests**
- Injected differences (missing, duplicate, amount mismatch, timing) are all detected and categorised.
- Nothing auto-adjusts.
- Re-running a payout batch after a crash creates no duplicate instruction.
- PAID only with a reference and proof.

## Phase 4: Merchant and developer platform (≈6–8 weeks)

**Scope**
- Onboarding with KYB case management.
- Market enablements and settlement destinations.
- Scoped API keys; webhooks v2 (SSRF-safe, replay console).
- Merchant portal pages for balances, statements, settlements and disputes; admin portal work queues.
- API reference and sandbox.

**Dependencies:** the frontend repositories; design decisions.

**Acceptance test:** a new test merchant onboards, receives keys, integrates via the docs, takes simulator payments, sees balances and settlement, and receives webhooks, all without staff database access.

## Phase 5: Provider and biller integrations

**Scope:** adapter contract plus capability declarations; harden existing adapters; contract tests; health and circuit breakers; biller framework demonstrated on ZESA and one more biller.

**Acceptance test:** one provider and one biller flow end to end against real test environments, with status recorded in the integration register.

## Phase 6: Risk and compliance

**Scope:** rules in **observe mode first**, then enforcement; screening with list management; case workflow; FIU reports to the confirmed formats; dispute timelines per the RBZ rules.

**Acceptance test:** a scenario library (velocity, structuring, sanctions hit, false positive) produces the expected alerts and cases. Access controls are tested.

## Phase 7: Regional architecture and ISO 20022

**Scope:**
- full country and entity model;
- a second, representative market configuration (no launch);
- canonical ↔ ISO 20022 mapping for the first verified message set (likely `camt.053` and the sponsor bank's payout format);
- conformance tests.

**Acceptance test:**
- an ISO 20022 statement or payment flow is mapped, validated against the official schema, processed and traced through the ledger;
- adding a sandbox country needs configuration plus adapters only.

## Phase 8: Additional products and automation

Wallets, agents, QR (via the National Switch scheme), USSD, WhatsApp, POS, FX conversions, **each only after its licensing question is answered**. Harvest the parked branch code.

## Phase 9: Security, scale and resilience

Independent penetration test, load and concurrency tests, DR exercise, restore drills, SLO measurement.

## Phase 10: Controlled launch and expansion

Pilot merchants, parallel reconciliation against legacy processes, regulatory approvals, then wider rollout.

---

## Critical path

```
Phase 0.5 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4 ──► Phase 10 (Zimbabwe pilot)
   │             │                        ▲            ▲
   │             └──► Phase 6 (observe) ──┘            │
   │                                                   │
   └──► Operating-model decision + sponsor bank ───────┘
Phase 5 runs alongside Phases 2–4 as credentials arrive
Phase 7 design work starts in Phase 1 (reference data), delivery after Phase 4
```

Durations are planning estimates for a small team, not commitments. They depend most on external access: Maven/CI, provider sandboxes, the sponsor bank and the regulatory answers.
