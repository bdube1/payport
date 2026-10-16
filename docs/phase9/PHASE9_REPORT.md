# Phase 9: security, scale and resilience

_Branch `phase2-payment-lifecycle`, 2026-10-16. Roadmap: "Penetration test, load and concurrency tests, DR,
restore drills, SLOs." Everything below runs in CI on every push, so it stays true._

## 1. Security

| Control | What | Evidence |
|---|---|---|
| **Authorization matrix** | Every `/api/v1/admin/**` route (generated from the running application's route table, so a new route is covered automatically) must refuse anonymous callers and customers and carry a role guard that does not admit customers; every `/api/v1/merchant/**` route must refuse customers | `SecurityResilienceScenarioTest.authorizationMatrix_…` |
| **Finding fixed** | Three admin controllers had **no role guard**: any signed-in customer could reach the ops dashboard, transaction search, customer statements, CSV export, refunds, fulfilment retry and float thresholds. Guarded now (`OpsController`, `WalletBalanceController`, `ProviderHealthController`), and the refund / retry operator is taken from the login instead of the request body (it was client-supplied, so the audit trail could be spoofed) | same test; code in this commit |
| Defence in depth | `/api/v1/admin/**` and `/api/v1/merchant/**` are closed **by URL** to the staff and merchant roles as well as by each method's `@PreAuthorize` | `SecurityConfig` |
| Headers | `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'…`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Permissions-Policy`, HSTS, `nosniff` | `securityHeaders_…` |
| CORS (R36) | Localhost origins only outside production; extra https origins by configuration | `Phase8UnitTest.cors_…` |
| Metrics | `/actuator/prometheus` only with `PAYPORT_METRICS_TOKEN` (404 when unset) | `securityHeaders_metricsTokenProtected_probesUp` |
| Signed integrations | POS (HMAC + nonce + clock window), WhatsApp (app-secret signature), USSD (HMAC), provider callbacks confirmed by status query | Phase 8 tests |
| Four-eyes in the database | Self-approval refused by CHECK constraints (approvals, FX rates, float deposits, settlement rules) | Phase 8 tests, `PlatformDbTest` |
| **Penetration test** | Scope and rules of engagement for the external tester | [`PEN_TEST_SCOPE.md`](PEN_TEST_SCOPE.md) — to commission |

**Dependency scan** (CI job `dependency-scan`, OSV database, report only). The first scan listed 25
advisories. Fixed by upgrading: Apache POI 5.2.5 → 5.4.1 (with commons-compress), Jackson 2 → 2.22.3,
Jackson 3 → 3.1.7, embedded Tomcat → 11.0.26, Bouncy Castle 1.78 → 1.86. Each override is commented in
`pom.xml` and is removed once Spring Boot manages that version or newer.
**Accepted, with mitigation:** `org.apache.xmlrpc` 3.1.3 (GHSA-r2pg-w96p-pcpj, deserialisation in the
XML-RPC client's fault handling; the library is end-of-life with no fixed release). It is used only by the
Econet airtime adapter, which talks to Econet's own endpoint over the private link; the risk is a
compromised Econet server. Replacing it with a minimal XML-RPC client is on the backlog (R76).

## 2. Scale and concurrency

| Test | Result (CI run 38950628724) |
|---|---|
| 30 simultaneous wallet transfers against money for 10 | exactly 10 succeed, 20 refused, balance 0, never negative |
| One payment confirmed by 10 simultaneous wallet payments | taken once (one wallet debit), payment CONFIRMED |
| Ledger: 200 parallel debits, same idempotency key from many threads, opposing transfers (existing `LedgerConcurrencyTest`) | no overdraft, no double post, no deadlock |
| Latency, 400 authenticated wallet reads from 8 concurrent clients on the CI runner | **p50 51 ms, p95 74 ms, p99 91 ms** |
| Staging load (open-model arrival rates, thresholds = SLOs) | `scripts/phase9/k6-staging.js`, run against staging before go-live |

Graceful shutdown (`server.shutdown=graceful`, 30 s) lets in-flight payments finish during rolling deploys;
liveness and readiness probes are at `/actuator/health/liveness` and `/readiness`.

## 3. Disaster recovery

`scripts/phase9/dr-drill.sh` backs up the database exactly as a backup job should (`mysqldump
--single-transaction --routines --triggers`), restores it into an empty database and proves the copy is
whole: identical row counts for every table, the ledger's definer procedures present, identical ledger
balances per category and currency, and `ledger_integrity_report()` giving the same answer on the copy.
It runs in CI after the ledger tests (job `ledger-db`) on the data they wrote: **PASS, restore 2 s**.

On production: run it on a non-production server against a real backup (the Phase 0.5
`restore-drill.sh` records the evidence), quarterly and after every schema change. RPO/RTO targets are in
the SLO document.

## 4. Service levels

[`SLO.md`](SLO.md): availability 99.9 %, latency objectives per endpoint class, payment-outcome and
callback objectives, ledger integrity, RPO ≤ 5 min / RTO ≤ 60 min, the alert rules and the error-budget policy.

## 5. Open (owner)

- Commission the external penetration test (scope above) on staging.
- Run the k6 staging test and the restore drill on production-like infrastructure; record the results.
- Provision `PAYPORT_METRICS_TOKEN` and the alert rules in the monitoring stack.
