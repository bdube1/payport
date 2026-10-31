# Payport Phase 0: Discovery and baseline assessment

_2026-10-15. Answers the 14 questions in §20 of the engineering prompt._

**Labels:**
- **[Tested]**: executed here and observed.
- **[Observed]**: read in code or config.
- **[User]**: reported by you.
- **[Assumed]**: assumption.
- **→**: recommendation.

**Supporting documents:**

| Doc | Content |
|---|---|
| 01 | Capability register |
| 02 | Risk register |
| 03 | Database assessment |
| 04 | Integration inventory |
| 05 | Build, test and migration report |
| 06 | Target architecture |
| 07 | Operating models and regulation |
| 08 | Roadmap |
| `docs/adr/` | Architecture decision records |
| `docs/PROJECT_STATUS.md` | Project status |

## Limits of this assessment (read first)

- **What I inspected:**
  - the gateway source you uploaded;
  - the `psp-foundation` branch I produced earlier in this project;
  - `payport-psp.zip` (the same code).
- **I could not:**
  - build with real dependencies (Maven Central refused by this environment's network policy);
  - run the application;
  - see the frontends, the live deployment, production data, the git history, or provider specifications.
- **What I did instead:**
  - installed a real MySQL 8.0.46;
  - applied every migration under production-like settings;
  - tested the database-level financial invariants.
- **Evaluating my own work:** the PSP branch is my earlier work. I have assessed it by the same standard as the gateway, and I correct my earlier claims where needed. Its documents said "Built" where the honest status is "implemented but not verified".

## 1. What Payport currently does [Observed, User]

A Spring Boot 3.2 / Java 21 / MySQL 8 application of about 26k lines that:
- sells airtime, data and bills (Econet, NetOne, Telecel; ZESA, Bulawayo City Council, ZOL, Nyaradzo, Moonlight through e-Solutions) to consumers;
- collects via EcoCash, OneMoney, O'mari and ZimSwitch cards;
- offers merchants onboarding, API keys, hosted checkout, payment links, webhooks, refunds and disputes (as records), a legacy settlement run, and statement reconciliation;
- serves staff with ops, finance and reporting endpoints.

It is a **gateway and aggregator without a ledger**. Money positions are inferred from transaction and session rows.

## 2. What works and has been verified

- **Verified by me:**
  - the gateway's 31 migrations apply cleanly;
  - the branch's migrations apply cleanly given one DB setting, and the upgraded schema equals a clean install;
  - core ledger database constraints work: idempotency, positive amounts, append-only on update/delete, four-eyes.
- **Verified by you:** the gateway's provider flows operate in production [User].
- **Not verified:** the application builds with real libraries; the application runs.

## 3. What is incomplete, duplicated, insecure or nonfunctional

**Critical, in the running gateway [Observed]:**
- **R1:** payment links and dashboard checkouts are simulated as paid and can be settled.
- **R2:** onboarding tracking endpoints let anyone read applications and upload KYB documents by guessing `MER-yyyyMMdd-NNNN`.
- **R3:** public, enumerable transaction lookup.
- **R4:** unauthenticated EcoCash/OneMoney callbacks.

**High, in the gateway:**
- no ledger;
- timeouts turned into failures;
- legacy settlement without approval or proof, using `double` maths;
- in-memory fulfilment queue;
- webhook SSRF;
- schedulers unsafe with more than one instance;
- broken Dockerfile;
- `ddl-auto=update`;
- almost no tests.

**On the branch:**
- about 19k lines never executed;
- untested controls wired into the live checkout;
- append-only bypassable via `TRUNCATE`/`DROP TRIGGER` **[Tested]**;
- DB does not enforce journal balance **[Tested]**;
- trigger migrations half-apply without a DB setting **[Tested]**;
- duplicate refund, dispute, settlement and reconciliation models;
- Zimbabwe hard-coding (74 PSP code lines with ZW-specific literals, plus the currency CHECK);
- QR non-conforming with the RBZ 2026 guidelines;
- products built ahead of licensing (wallets, agents, FX).

## 4. What to retain

- All production provider and biller adaptors.
- The gateway's merchant onboarding, API keys, checkout and webhooks (after the security fixes).
- Statement parsers.
- **From the branch** (as designs and code to harvest, not deploy as-is):
  - ledger domain and posting rules;
  - outbox, idempotency keys, maker-checker approvals, audit chain;
  - callback inbox and uncertain-payment resolver;
  - settlement engine structure;
  - the spec-driven entity/DDL generator;
  - the validated V32–V40 schema as a starting point.

## 5. What to fix, refactor or rebuild, and why

| Area | Action | Why |
|---|---|---|
| R1–R4 | **Fix now** (Phase 0.5) | Money and data exposure today |
| Transaction status model | **Refactor** into payment / attempt / fulfilment state machines | One field cannot represent independent outcomes; causes wrong decisions |
| Legacy settlement | **Replace** after a parallel run | No controls; float maths; settles test money |
| Fulfilment queue | **Rebuild** on durable jobs | Jobs lost on restart |
| Ledger (branch) | **Harden**: multi-entity, least privilege, DB-level balance (ADR-0004), concurrency-tested | Must be a trustworthy source of truth |
| Refunds/disputes/recon duplicates | **Consolidate** to one owner each (ADR-0003) | Two truths about money |
| Country assumptions | **Refactor** to reference data (ADR-0006) | Expansion without rewrites |
| QR, wallets, agents, USSD, WhatsApp, FX on the branch | **Park** until licensed and scheduled | Regulatory exposure; out of phase |
| Deployment | **Rebuild** reproducible build, container, CI | Cannot verify anything without it |

## 6. Can the existing architecture support the PSP without replacement?

**Yes, with refactoring.** The application structure is conventional and sound. The adaptors are reusable, and MySQL with Flyway is adequate. What is missing is the financial core and the separation of concerns, not the platform. → Evolve it into a modular monolith (ADR-0001). Do not rewrite. Do not deploy the branch as a unit (ADR-0002).

## 7. Principal risks

See doc 02 for the full register.
- **Financial:** fake-paid settlements (R1); no ledger; timeout-as-failure; duplicated sources of truth.
- **Security:** R2, R3, R4, SSRF, plaintext secrets, DB ports and TLS.
- **Database:** schema drift from `ddl-auto`; trigger and privilege issues; no tested restore.
- **Integration:** no simulators or contract tests; in-memory fulfilment; a new attempt can be sent while the outcome is unknown.
- **Regulatory:** licence and safeguarding position unknown; QR rules; data-protection licensing.

## 8. Target architecture

Doc 06. A modular monolith with modules for:
- identity, countries, merchants;
- payments, orchestration, providers, fulfilment;
- billing, ledger;
- settlement, reconciliation, disputes, FX;
- risk/compliance, notifications/webhooks;
- platform.

Channels never touch the ledger. Test and live modes are separated end to end (ADR-0007).

## 9. Automation with the greatest benefit

From doc 06 §8, in order:
1. uncertain-payment resolution;
2. statement auto-import and matching;
3. eligible-funds and settlement drafting with threshold approvals;
4. durable fulfilment;
5. payout status tracking;
6. provider health and safe routing;
7. a webhook replay console;
8. daily integrity and safeguarding checks.

## 10. Multi-country, multi-currency, ISO 20022

- **Reference data and policy sets.** Countries, currencies, legal entities, calendars and versioned policy sets; country logic only in `CountryPolicy` implementations.
- **Ledger.** Per entity, minor units from the currency table, FX through clearing accounts with stored rates.
- **ISO 20022.** A mapping layer onto a Payport canonical model. Versions are chosen per verified implementation guide; the first likely use is `camt.053` statements and sponsor-bank payouts (ADR-0005).

## 11. Unified merchant onboarding and cross-border settlement

- **One account, one integration, one dashboard.** Per-market enablements (each tied to a legal entity and approved separately) and verified settlement destinations.
- **Local settlement** per market is designed in.
- **Cross-border settlement** is a separately approved **route**, needing an exchange-control-compliant channel (bank, ADLA, PAPSS via a bank). It is never a toggle.

## 12. Operating models

Doc 07 compares direct PSP, partner-led, aggregation and hybrid.
→ **Design for hybrid.**
- **Zimbabwe launch:** partner-led with a sponsor bank, unless Payport already holds or is close to an RBZ licence. Per SI 17 of 2025, non-bank applicants apply with the support of a locally authorised institution, so a bank relationship is needed either way.
- **Other markets:** partner-led or aggregation first.

## 13. Roadmap

Doc 08. **Phase 0.5** (containment + real build), then Phases 1–10 as in your prompt, each with measurable acceptance tests.

The critical path runs through:
- Maven/CI access;
- the production schema;
- the sponsor bank's payout specification;
- the operating-model decision.

## 14. What I need from you

| # | Need | Unblocks |
|---|---|---|
| 1 | **Check R1 in production now**: `TEST_SIM_` provider references on payment-link sessions, and whether any were settled | Containment |
| 2 | Approval of this assessment, ADR-0001–0007 and the roadmap (or changes) | Phase 0.5 |
| 3 | Real build: let this environment reach Maven Central, **or** share `mvn -B verify` logs from your CI or a developer machine | Every verification gate |
| 4 | Frontend repositories (consumer app, merchant dashboard, admin) | R1/R3 fixes, Phase 4 |
| 5 | Production schema dump (no data); later an anonymised data copy | Migration safety (D1) |
| 6 | Deployment description: servers, proxy/WAF, how `.env` is managed, backups | Security and DR baseline |
| 7 | Payport's licence/registration status, current trust/settlement bank arrangement, and counsel's view on which model to pursue | Phases 3, 8 |
| 8 | Provider sandbox credentials and specs (EcoCash, OneMoney, O'mari, ZimSwitch, e-Solutions; Telecash/InnBucks if wanted) | Phases 2, 5 |
| 9 | Sponsor bank payout and statement formats | Phase 3 |
| 10 | Finance lead to sign off the chart of accounts; tax adviser on IMTT/VAT treatment | Phase 1 |
| 11 | Target second market (for Phase 7 design) | Phase 7 |

**I have stopped here.** No architectural changes were made: this branch adds documents only. On your approval I will start Phase 0.5.
