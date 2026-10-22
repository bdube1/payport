# PayPort PSP: project status

_Last updated: 2026-10-22 (Phase 8 completed, Phase 9). Living document._

## Current phase
**Phase 8 completed and Phase 9 (security, scale, resilience) done**, on `phase2-payment-lifecycle`. Everything
new ships **off** and fails closed; the owner records licence answers, configures credentials and switches
each product on in the portal after internal checks: [`docs/runbooks/SWITCH_ON.md`](runbooks/SWITCH_ON.md).
- `docs/phase8/PHASE8_REPORT.md`: wallets, agents, FX conversions, POS, WhatsApp, MTN MoMo / Airtel Money, provider settlements;
- `docs/phase9/PHASE9_REPORT.md`, `SLO.md`, `PEN_TEST_SCOPE.md`;
- `docs/platform/PLATFORM_CONTROLS.md`: markets, currencies, channels, product lines from the portal;
- API contract: `docs/api/api-contract.json` (366 routes, equal to the running application).

**Combined test run (backend run 39791437666, all jobs green; frontends green at their latest commits):**
- `build-and-test`: **214 passed, 0 failed** (106 database tests run in `ledger-db`), including `Phase8CompletionScenarioTest`, `Phase8UnitTest`, `SecurityResilienceScenarioTest` and every earlier suite.
- `ledger-db`: **106 / 106**; DR drill: **PASS** (backup → restore → identical rows, procedures, balances, integrity; restore 3 s).
- `migrations-production-like` (V1–V52, binlog, non-SUPER user): pass. `dependency-scan`: 1 accepted advisory (R76).
- Latency on CI (400 wallet reads, 8 clients): p50 38 ms, p95 59 ms, p99 71 ms.
- Consumer, mobile, ops and merchant (#2) CI green with the API contract check (366 routes); merchant #1 re-checked locally against the same contract.

| Item | Status |
|---|---|
| Wallets | **Built**: ledger-authoritative balances, top-up, PIN-confirmed pay / transfer / convert, refunds to wallet, tier limits, freeze. Off until licence answer + switch. |
| Agents | **Built**: compliance approval, four-eyes float, cash-in, single-use cash-out codes, commission. Off. |
| FX conversions | **Built**: between a customer's own wallets at an approved rate, one journal through FX_CLEARING. Off. |
| POS | **Built**: signed terminals, sales as checkout sessions (QR / wallet / mobile money); cards fail closed until an acquirer is configured. Off. |
| WhatsApp | **Built**: signed Cloud API webhook, USSD menus, de-duplication. Off; needs Meta credentials. |
| New-market rails | **Built**: MTN MoMo and Airtel Money adapters, status-query confirmed, currencies configurable. Fail closed until credentials. |
| Provider settlements | **Built**: four-eyes rules book rail settlements found on camt.053 statements. |
| Frontends | Consumer and mobile wallet; merchant agent and POS; ops wallets, agents, POS/WhatsApp, settlement rules, ledger. All green in CI with the contract check. |
| Phase 9 security | Authorization matrix over every admin/merchant route (found and fixed 3 unguarded admin controllers, R75); URL-level role rules; CSP and other headers; CORS by environment (R36 closed); token-protected Prometheus; dependency scan with fixes (R77). |
| Phase 9 scale | Wallet double-spend and one-payment-many-confirmations tests (they found a connection-pool starvation in wallet postings, fixed: the journal now posts on the operation's own transaction); latency p95 59 ms on CI; k6 staging script with SLO thresholds. |
| Phase 9 resilience | DR drill (backup → restore → verify rows, procedures, balances, integrity) in CI: PASS; graceful shutdown; probes. |

**Owner inputs (configuration, not code)**
- Licence answers recorded in the portal by two compliance officers per line (the code never sets them).
- Credentials in the deployment environment: WhatsApp, MTN MoMo, Airtel Money, USSD secret, `PAYPORT_DATA_KEY`, `PAYPORT_METRICS_TOKEN`, `EAS_PROJECT_ID`.
- Bank accounts (settlement / safeguarding) entered in Markets & Currencies; the placeholders stay until then and block going LIVE.
- Confirm PROVISIONAL parameters (wallet limits, agent commission, expiries) in Compliance → Rules & Parameters.
- Commission the external penetration test; run the k6 test and a restore drill on production-like infrastructure.

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
- Provider sandboxes (EcoCash, OneMoney, ZimSwitch, Omari, MTN MoMo, Airtel Money, WhatsApp Cloud API): adapters are built from the published specs and fail closed; the integration register stays SPEC_ONLY until sandbox evidence is recorded in the ops console.
- Production backup restore and staging load test (scripts ready: `restore-drill.sh`, `dr-drill.sh`, `k6-staging.js`).
- External penetration test (scope: `docs/phase9/PEN_TEST_SCOPE.md`).

## Defects and blockers
| ID | Item | Severity | Status |
|---|---|---|---|
| R1 | Payment links / dashboard checkouts simulated as paid | Critical | **Fixed on branch**, not deployed. Exposure report ready. Your production check is still outstanding. |
| R2 | Public onboarding tracking endpoints enumerable and writable | Critical | **Fixed on branch** (status-only + 410) |
| R3 | Public transaction lookup exposed PII and **ZESA tokens** | Critical | **Fixed on branch** (token + public view). Full closure needs the consumer app to send the token, then `TRANSACTION_TOKEN_ENFORCE=true`. |
| R4 | Unauthenticated EcoCash/OneMoney callbacks | Critical | **Fixed on branch** (verify by provider query) |
| R6 | Timeout → FAILED after 30 min | High | **Fixed on branch** (no time-based failure; back-off; 72 h manual) |
| R35 | `/my-history` logged all request headers (cookies, keys) at INFO | High | **Fixed on branch** |
| R36 | CORS allows `http://localhost:*` origins with credentials in the production config | Low | **Fixed (Phase 9)** |
| B1 | No real build possible here (Maven Central blocked) | Blocker | **Resolved**: GitHub Actions CI is the build gate |
| B2 | Frontend repositories not available | Blocker for full R3 closure | **Resolved**: all four frontends integrated and contract-checked |
| B3 | Production schema dump (structure only) | Blocker for D1 | Requested |
| B4 | Real backup for the restore drill | Blocker for D8 | DR drill automated in CI; a run against a real backup is the owner's (no production data on this platform) |
| B5 | Licence / operating model | Blocker for Phases 3 and 8 | Owner confirms all licensed; answers are recorded in the portal per line |

## Integration status
Unchanged from Phase 0 (doc 04). EcoCash and OneMoney callbacks now trigger status queries; their behaviour against real provider responses is unverified until deployed or sandbox-tested.

## Migration status
- `main` lineage: V1–V31 in production (assumed); **V32 ready** (tested locally).
- `psp-foundation` migrations will be renumbered from V33 when harvested.

## Automation status
The uncertain-payment reconciler (EcoCash, OneMoney) is the first containment automation. It is single-instance and keeps its back-off clock in memory.

## Next actions
1. **Owner:** record the licence answers, enter the bank accounts and credentials, confirm the provisional parameters, then switch each line on (runbook `docs/runbooks/SWITCH_ON.md`).
2. **Owner:** provider sandbox runs and record the evidence; external penetration test; staging k6 run; restore drill on a real backup.
3. **Next build phase:** whatever the roadmap sets after Phase 9; open engineering items: replace the XML-RPC client in the Econet adapter (R76), card acquirer adapter when contracted, national QR scheme parameters.
