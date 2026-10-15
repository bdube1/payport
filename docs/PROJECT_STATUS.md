# PayPort PSP: project status

_Last updated: 2026-10-15 (Phase 0.5 implementation). Living document; updated at the end of every phase and whenever a blocker changes._

## Current phase
**Phase 0.5: containment and verified baseline.** Code complete on branch `phase0.5-containment` (based on `main`), awaiting:
- your CI run, or Maven access;
- frontend changes;
- the production artefacts listed below.

Phase 0 was approved on 2026-10-15, including all of ADR-0001 to ADR-0007.

## Branches
| Branch | Content | Deployable? |
|---|---|---|
| `main` | Gateway as uploaded | In production (per you) |
| `phase0.5-containment` | R1–R4 and R6 fixes, V32, CI workflow, scripts, docs | **Yes**, after CI passes and the steps in `docs/phase0.5/PHASE0.5_CONTAINMENT_RELEASE.md` |
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
