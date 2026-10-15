# Phase 0: Financial, security, database, integration and regulatory risk register

**Severity:**
- **Critical:** money or customer data at risk now.
- **High:** must be fixed before the PSP takes any merchant's funds through the ledger.
- **Medium:** fix within the relevant phase.
- **Low:** track.

"Where" is **G** for the existing gateway and **P** for the `psp-foundation` branch.

## Critical: act now, independent of the roadmap

| ID | Risk | Where | Evidence | Containment now | Proper fix |
|---|---|---|---|---|---|
| R1 | Payment links and dashboard checkouts are simulated as paid without charging the customer, then become settleable | G, P | [Observed] `CheckoutController.pay`: `isTestMode = true` when `apiKeyId` is null; `PaymentLinkService` passes `null` | Check production data; disable payment links, or treat no-key sessions as live | Explicit `livemode` on every session, from the creating credential or merchant mode; settlement and ledger exclude test-mode; tests for each creation path |
| R2 | Anyone can upload KYB documents into, and read review notes of, any merchant application by guessing `MER-yyyyMMdd-NNNN` | G | [Observed] `PublicMerchantTrackingController` is `permitAll` | Add a high-entropy tracking token (emailed) or require the applicant login; rate-limit | Applicant accounts; document upload only by an authenticated applicant; audit |
| R3 | Unauthenticated, enumerable transaction lookup | G | [Observed] `/api/v1/transactions/*` is `permitAll` | Return minimal fields; rate-limit | Per-transaction access token issued to the paying browser |
| R4 | Forged EcoCash/OneMoney callbacks can mark payments paid | G (fixed in P, unverified) | [Observed] | Deploy only the callback verify-by-query fix, after testing against the provider sandbox | Phase 2 callback framework |

## High

| ID | Risk | Where | Evidence | Mitigation / phase |
|---|---|---|---|---|
| R5 | No financial source of truth; balances derived from transaction rows; legacy settlement uses `double` and has no approval or proof | G | [Observed] | Phase 1 ledger; Phase 3 settlement |
| R6 | Timeout treated as failure (EcoCash reconciler gives up to FAILED after 30 min) | G | [Observed] | Phase 2. The branch has a fix (unverified) |
| R7 | Append-only ledger defeated by `TRUNCATE` / `DROP TRIGGER` under the app's DB privileges | P | **[Tested]** | Phase 1: least-privilege runtime user, separate migration user, external anchoring of chain seals |
| R8 | Ledger balance not enforced by the database | P | **[Tested]** | ADR-0004 decision; continuous detection is mandatory either way |
| R9 | V32/V33 half-apply under Flyway when binlog is on and the user lacks SUPER | P | **[Tested]** | Phase 1: precondition check before any DDL; DBA-applied trigger script |
| R10 | 19k lines of PSP code never executed; untested controls (merchant gates, routing, fraud blocking) wired into the live checkout path | P | [Observed] | **Do not deploy `psp-foundation` as is.** Re-enter it module by module behind feature flags, in shadow mode first (ADR-0002) |
| R11 | Schedulers are not cluster-safe; a second instance would double-run settlement drafting, recon and resolvers | G, P | [Observed] | Phase 1 job framework with DB leases |
| R12 | In-memory fulfilment queue loses jobs on restart (airtime/bills paid but not delivered) | G | [Observed] | Phase 2 durable jobs. The branch has a recovery sweeper (unverified) |
| R13 | Merchant webhook SSRF: merchant-supplied URL can target internal hosts or metadata endpoints | G | [Observed] | Phase 4: https only, resolve and block private/link-local ranges, egress proxy |
| R14 | Secrets in plaintext: webhook secrets, `.env` files on the VM; DB without TLS (`useSSL=false`) | G | [Observed] | Phase 1 secrets manager; encrypt webhook secrets; TLS to DB |
| R15 | MySQL and Redis published on host ports in `docker-compose.yml` | G | [Observed] | Confirm the real deployment; never expose DB ports publicly |
| R16 | No meaningful automated tests; every change to money code is unverified | G, P | [Observed] | Quality gates from Phase 1 (Testcontainers MySQL, simulator contract tests) |
| R17 | Build cannot be reproduced in this environment; Spring Boot 3.2.0 out of OSS support | G, P | **[Tested]** | CI with real `mvn verify`; upgrade to a supported Boot line in Phase 1 |
| R18 | QR implementation does not follow the RBZ QR Guidelines (scheme MAI ID via National Switch; closed-loop QR to cease) | P | [Observed] vs the guidelines effective 11 Mar 2026 | Do not launch QR until a scheme membership or acquirer route is agreed |
| R19 | PayPort's licence position unknown: the e-money wallets, agents and FX built on the branch may be activities PayPort is not authorised to perform | P | [Not verified] | Operating-model decision (doc 07) before Phase 8 products |
| R20 | Trust/safeguarding arrangement unknown: who holds collected funds, and in which account | G, P | [Not verified] | Operating-model decision; ledger cash accounts must mirror the real accounts |

## Medium

| ID | Risk | Where | Mitigation |
|---|---|---|---|
| R21 | Duplicate sources of truth: refunds, disputes, reconciliation and settlement exist in both the gateway and the PSP | P | ADR-0003: one owner per concept; gateway records become projections or are retired |
| R22 | Single status field mixes payment, fulfilment and refund states | G | Phase 2: separate payment attempt, posting, fulfilment and settlement states |
| R23 | Zimbabwe assumptions hard-coded (74 PSP code lines contain ZW-specific literals) | P | Phase 1 country/currency reference data; Phase 7 full model |
| R24 | `ddl-auto=update` and `allow-bean-definition-overriding=true` hide schema drift and wiring errors | G | Phase 1: `validate`, overriding off |
| R25 | Docker build broken; deployment is a single VM via `start.sh` | G | Phase 1 reproducible build; Phase 9 resilience |
| R26 | Placeholder business values (tier limits, tariffs, routing costs, fraud thresholds) could reach production | P | Values must come from approved, versioned config; refuse to start with placeholders in production mode |
| R27 | Tax handling: IMTT 2% USD / 1.5% ZiG and VAT 15.5% from 1 Jan 2026 (Finance Act No. 7 of 2025, as reported); whether IMTT is charged by PayPort or by the paying institution depends on the flow | P | Confirm with a tax adviser; effective-dated, approved tax configuration |
| R28 | Data protection: Cyber and Data Protection Act (POTRAZ). Controller licensing and a DPO (SI 155 of 2024), breach notice to POTRAZ within 24 h, notice before cross-border transfers | G, P | Regulatory register (doc 07); hosting decision |
| R29 | RBZ incident reporting for QR participants: security breaches within 3 hours; fraud reports within 24 hours | P | Incident runbook timings once in scope |

## Low

| ID | Risk |
|---|---|
| R30 | Unused Redis container; broken Docker health check |
| R31 | AI assistant shares the API process and quota with payment traffic |

## Correction to my earlier delivery

The `psp-foundation` documents (`PSP_ARCHITECTURE.md`, `INTEGRATION_STATUS.md`) label many features **"Built"**. Under your definition of done they are **implemented but not verified**, and the QR module is non-conforming. Those documents now carry a notice pointing to this register.
