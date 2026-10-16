# PayPort PSP: project status

_Last updated: 2026-10-16 (Phase 2 payment lifecycle). Living document; updated at the end of every phase and whenever a blocker changes._

## Current phase
**Phase 3 (reconciliation and settlement) and the owner's Phase 2 review changes**, on `phase2-payment-lifecycle`:
- Stripe-style payment intents and attempts, with per-provider payment windows instead of 72 h of "pending".
- On-the-spot outcomes for USSD: SUCCEEDED, FAILED, TIMED_OUT or NO_RESPONSE.
- Idempotency keys.
- SMS only on success.
- The provider's own messages.
- Retry rules from the provider specs.
- Staff skip-payment endpoints removed.

See `docs/adr/0011-payment-intents-and-outcomes.md`, `docs/phase2/PROVIDER_ERROR_CODES.md` and `docs/phase3/PHASE3_REPORT.md`. CI evidence: see the latest run on the branch.

### Phase 2
**Phase 2: payment lifecycle. Code complete and CI-verified on `phase2-payment-lifecycle`** (see `docs/phase2/PHASE2_REPORT.md`, ADR-0010, `docs/runbooks/PAYMENT_OPERATIONS.md`).

| Phase 2 acceptance | Status |
|---|---|
| Duplicate, out-of-order and before-response callbacks → one effect | **Met** (`PaymentLifecycleScenarioTest`, real MySQL 8) |
| Timeout → late success / definite failure; never failed by time | **Met** |
| Provider down; concurrent identical `/pay` → one push; no blind retry | **Met** |
| Durable delivery; unknown delivery never re-sent | **Met** (`FulfilmentJobScenarioTest`) |
| Behaviour against real provider sandboxes | **Not verified**: needs sandbox or staging runs with forced timeouts |

CI run 38819385755 (2026-10-16), all three jobs green:
- `build-and-test`: **106 passed, 0 failed**; the required suites are guarded against skipping.
- `ledger-db`: **106 / 106**.
- `migrations-production-like` (V37, V38): pass.

Found and fixed in Phase 2:
- R45: forgeable ZimSwitch webhooks.
- R46: unaudited staff "skip payment" endpoint, now off by default.
- R47–R49 and R51 (risk register).

### Phase 1 (previous)
**Phase 1: financial foundation. Code complete on `phase1-financial-foundation`; exit evidence partial** (see `docs/phase1/PHASE1_REPORT.md`).

| Exit criterion | Status |
|---|---|
| E1 balanced scenarios | **Met**: 55 scenarios on real MySQL |
| E2 concurrency | **Met**: no overdraft, no double-post, no deadlock |
| E3 atomicity | **Met** |
| E4 least privilege | **Met**: 19 bypass attempts denied |
| E5 shadow = legacy on a week of production-copy data | **Open**: needs a production data copy; tooling ready |
| E6 supported Spring Boot | **Met on branch `phase1-spring-boot-upgrade`**: Boot 4.1.1, all CI jobs green with no skipped required tests (`docs/phase1/SPRING_BOOT_4_UPGRADE.md`). Staging soak with real frontends still recommended |

**Phase 0.5:** concluded on 2026-10-15 by owner decision. Its exit items are still open:
- CI run (B1);
- schema drift check (B3);
- restore drill (B4);
- consumer-app token (B2);
- R1 production check.

Until CI passes, no Phase 0.5 or Phase 1 change counts as built.

## CI evidence (GitHub Actions, bdube1/payport, 2026-10-15)

Real Maven build with real dependencies, on GitHub-hosted runners.

| Branch | Job | Result |
|---|---|---|
| `phase0.5-containment` | build-and-test (`mvn verify`) | **57 / 57 passed**, including the Testcontainers migration test (real Flyway + MySQL 8) and the full-application smoke test |
| `phase0.5-containment` | migrations-production-like (binlog, non-SUPER user) | **Pass** |
| `phase1-financial-foundation` | build-and-test (`mvn verify`) | **68 passed, 0 failed**; the 106 database tests are skipped here by design and run in `ledger-db` |
| `phase1-financial-foundation` | ledger-db (real MySQL 8, migration user + least-privilege app user) | **106 / 106 passed** |
| `phase1-financial-foundation` | migrations-production-like | **Pass** |

This closes **B1** (no real build) and verifies Spring wiring, application start-up and the security chain: `ApplicationSmokeTest` boots the whole application on MySQL 8.

**Found by CI and fixed:**
- **Scripts not executable.** The repository has `core.fileMode=false`, so the scripts were recorded without the executable bit.
- **Payment API reported unhealthy over email.** `/actuator/health` returned **503 DOWN** whenever SMTP was unavailable, although the database and disk were up. A container or load-balancer health check would restart the payment API, or take it out of service, over an email problem. Mail is now excluded from the health status (`management.health.mail.enabled=false`).

**Still open:**
- E5: shadow ledger vs legacy on a production data copy;
- E6: Spring Boot upgrade, now unblocked because CI exists;
- B2: consumer-app token change;
- B3: schema drift check;
- B4: restore drill.

## Branches
| Branch | Content | Deployable? |
|---|---|---|
| `main` | Gateway as uploaded | In production (per you) |
| `phase0.5-containment` | R1–R4 and R6 fixes, V32, CI workflow, scripts, docs | **Yes**, after CI passes and the steps in `docs/phase0.5/PHASE0.5_CONTAINMENT_RELEASE.md` |
| `phase2-payment-lifecycle` | Phase 1 + Boot 4.1.1 + Phase 2 lifecycle (V37–V38) | **Yes**, after a staging soak with provider sandboxes (timeouts forced) and the decisions in the Phase 2 report |
| `phase1-spring-boot-upgrade` | Phase 1 + Spring Boot 4.1.1 (3.x is out of support) | **Yes**, after a staging soak with the real frontends |
| `phase1-financial-foundation` | Ledger (V33–V36), shadow posting, jobs, approvals, tax rates, admin API, `ledger-db` CI job | **Yes**, after CI passes. Shadow posting is off by default (`LEDGER_SHADOW_ENABLED`). |
| `phase0-discovery` | Phase 0 documents (on top of `psp-foundation`) | Documents only |
| `psp-foundation` | Earlier PSP build; harvested phase by phase (ADR-0002) | **No** |

## Verified (with evidence)
| Item | Evidence |
|---|---|
| Gateway migrations V1–V31 apply on MySQL 8.0.46 | Phase 0 report 05 |
| **V32** applies after V1–V31 (binlog on, non-SUPER user); backfill correct for all six historical cases; NOT NULL rejects sessions without a mode; rollback and re-backfill scripts work | Phase 0.5 release doc §4 [Tested] |
| 16 changed files compile with real Lombok (Spring/JPA/Jackson from API stubs) | [Tested, partial] |
| 31 unit tests for the containment rules pass on **real JUnit 5.10.1** | [Tested] |
| Migration, schema-drift and restore-drill scripts work against local MySQL | [Tested] |

## Not verified
- `mvn verify` with real dependencies.
- The Testcontainers migration test.
- Application start-up and end-to-end HTTP flows.
- Provider query behaviour against EcoCash and OneMoney: the status-query code paths are existing adaptor code, now also used on callbacks.

## Defects and blockers
| ID | Item | Severity | Status |
|---|---|---|---|
| R1 | Payment links / dashboard checkouts simulated as paid | Critical | **Fixed on branch**, not deployed. Exposure report ready. Your production check is still outstanding. |
| R2 | Public onboarding tracking endpoints enumerable and writable | Critical | **Fixed on branch** (status-only + 410) |
| R3 | Public transaction lookup exposed PII and **ZESA tokens** | Critical | **Fixed on branch** (token + public view). Full closure needs the consumer app to send the token, then `TRANSACTION_TOKEN_ENFORCE=true`. |
| R4 | Unauthenticated EcoCash/OneMoney callbacks | Critical | **Fixed on branch** (verify by provider query) |
| R6 | Timeout → FAILED after 30 min | High | **Fixed on branch** (no time-based failure; back-off; 72 h manual) |
| R35 | `/my-history` logged all request headers (cookies, keys) at INFO | High | **Fixed on branch** |
| R36 | CORS allows `http://localhost:*` origins with credentials in the production config | Low | Open (Phase 1) |
| B1 | No real build possible here (Maven Central blocked) | Blocker | Need CI on GitHub or `mvn verify` logs |
| B2 | Frontend repositories not available | Blocker for full R3 closure | Requested |
| B3 | Production schema dump (structure only) | Blocker for D1 | Requested |
| B4 | Real backup for the restore drill | Blocker for D8 | Requested |
| B5 | Licence / operating model | Blocker for Phases 3 and 8 | Requested |

## Integration status
Unchanged from Phase 0 (doc 04). EcoCash and OneMoney callbacks now trigger status queries; their behaviour against real provider responses is unverified until deployed or sandbox-tested.

## Migration status
- `main` lineage: V1–V31 in production (assumed); **V32 ready** (tested locally).
- `psp-foundation` migrations will be renumbered from V33 when harvested.

## Automation status
The uncertain-payment reconciler (EcoCash, OneMoney) is the first containment automation. It is single-instance and keeps its back-off clock in memory.

## Next actions
1. **You:**
   - push this repository to GitHub, or give me CI access, so `ci.yml` runs;
   - share a structure-only production dump and a recent backup;
   - share the frontend repositories.
2. **Me, on CI results:** fix anything the real build finds, then report the Phase 0.5 exit evidence.
3. **After the Phase 0.5 exit is approved:** Phase 1 (financial foundation).
