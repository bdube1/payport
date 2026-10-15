# PayPort PSP: project status

_Last updated: 2026-10-15. Living document; updated at the end of every phase and whenever a blocker changes._

## Current phase
**Phase 0 (Discovery and baseline): complete, awaiting approval.** No architectural changes start until the roadmap is approved (prompt §20).

## Verified (with evidence)
| Item | Evidence |
|---|---|
| Original gateway migrations V1–V31 apply cleanly to MySQL 8.0.46 | Report 05 §5 [Tested] |
| Branch migrations V32–V40 apply cleanly (clean and upgrade path), **given** `log_bin_trust_function_creators=1` | Report 05 §5 [Tested] |
| DB enforces: unique ledger idempotency key, positive line amounts, append-only on UPDATE/DELETE, four-eyes approvals | Report 05 §5 [Tested] |
| 33 PSP pure-logic unit tests pass (through a test shim, not real JUnit) | Report 05 §3 [Tested, weak] |

## Not verified
- Build with the real dependencies (Maven Central blocked in this environment).
- Application start-up.
- Any end-to-end flow.
- Any provider behaviour.
- Everything in the capability register marked "Implemented but not verified".

## Work in progress
None. Waiting for approval.

## Defects and blockers
| ID | Item | Severity | Owner | Status |
|---|---|---|---|---|
| R1 | Payment links / dashboard checkouts simulated as paid (test mode by default) | **Critical** | — | Reported to you 2026-10-15; awaiting production data check |
| R2 | Public merchant onboarding tracking endpoints enumerable; document upload by anyone | **Critical** | — | Open |
| R3 | Public, enumerable transaction lookup | Critical | — | Open |
| R4 | Unauthenticated EcoCash/OneMoney callbacks | Critical | — | Fix exists on the branch, unverified |
| B1 | No real build possible here | Blocker | You / me | Need Maven access or CI logs |
| B2 | Frontend repositories not available | Blocker for Phase 0.5/4 | You | Requested |
| B3 | Production schema dump not available | Blocker for Phase 1 | You | Requested |
| B4 | Licence / operating model unknown | Blocker for Phases 3 and 8 | You + counsel | Requested |

Full list: `docs/phase0/02_RISK_REGISTER.md`.

## Integration status
See `docs/phase0/04_PROVIDER_AND_BILLER_INTEGRATION_INVENTORY.md`.
- **Production [User]:** EcoCash, OneMoney, O'mari, ZimSwitch, Econet/NetOne/Telecel airtime, e-Solutions billers, Africa's Talking SMS.
- **Everything else:** level 0–1.

## Migration status
V32–V40 exist on `psp-foundation` and have **not** been applied to any real environment. They will be reworked before first application (doc 03).

## Automation status
None of the target automations (doc 06 §8) is verified in operation.

## Decisions
ADR-0001 to ADR-0007 are **proposed** (`docs/adr/`).

## Next actions (on approval)
Phase 0.5: containment of R1–R3, CI with a real build, production schema diff, and a restore drill. Acceptance criteria are in doc 08.
