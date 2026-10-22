# Service level objectives

_Phase 9. These are the targets the owner signs off before go-live; the metrics that measure them are
exported at `/actuator/prometheus` (bearer `PAYPORT_METRICS_TOKEN`) and the staging load test
(`scripts/phase9/k6-staging.js`) fails when a latency or error objective is breached._

| # | Objective | Target (30-day window) | Measured by |
|---|---|---|---|
| S1 | API availability (non-5xx share of requests, excluding provider outages answered with 503 PROVIDER_UNAVAILABLE / CHANNEL_UNAVAILABLE) | 99.9 % | `http_server_requests_seconds_count{status!~"5.."}` / all |
| S2 | Public reads (catalogue, outcomes) latency | p95 < 300 ms, p99 < 800 ms | `http_server_requests_seconds_bucket{uri=~"/api/v1/products.*\|/api/v1/transactions/{reference}/outcome"}` |
| S3 | Payment initiation latency (`POST /transactions/initiate`) | p95 < 500 ms, p99 < 1.5 s | same histogram, `uri="/api/v1/transactions/initiate"` |
| S4 | Wallet operations latency (transfer, pay, convert) | p95 < 500 ms | `uri=~"/api/v1/wallet/.*"` |
| S5 | Payment outcome known (SUCCESS/FAILED) | 99 % within the payment window; 100 % resolved or handed to ops within 24 h | resolver and watchdog jobs; attention queue |
| S6 | Callback processing | 99.9 % of valid callbacks stored before the 2xx | callback inbox (`callback_inbox`) |
| S7 | Ledger integrity | 0 violations, checked every run of the integrity job | `ledger_integrity_report()`; ops Ledger page |
| S8 | Recovery | RPO ≤ 5 min (binlog shipping), RTO ≤ 60 min | DR drill (`scripts/phase9/dr-drill.sh`, CI job `ledger-db`) |

**Error budget.** S1 at 99.9 % allows 43 minutes of failing requests per 30 days. When half the budget is
spent, non-urgent releases stop until the cause is fixed.

**Alerts (Prometheus rules to configure in the monitoring stack).**
- 5xx rate > 1 % for 5 min (page).
- p95 of `/api/v1/transactions/initiate` > 1 s for 10 min (page).
- `up == 0` or readiness probe failing for 2 min (page).
- Integrity job reports violations (page, finance + engineering).
- Provider circuit open for > 15 min (ticket; ops sees it on the Integrations page).

**CI evidence.** `SecurityResilienceScenarioTest.latency_p95OfAnAuthenticatedRead_under8ConcurrentClients`
prints p50/p95/p99 for 400 wallet reads from 8 concurrent clients on the CI runner (the figure is in the
build's annotations); it is bounded at 1.5 s there because CI runners are shared, not as the SLO.
