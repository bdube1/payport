# Phase 11: staging integration and acceptance runbook

_Approved by the owner on 2026-10-22. Scope: staging integration and acceptance only. No product features,
no production activation, no live financial operations without the owner's explicit approval._

## 0. Before you pull: read this

`main` now contains Phases 0.5 to 10. The first time this build starts, **Flyway applies migrations V32 to
V52 to whatever database `DB_URL` points at**. Those migrations add the ledger, change tables and backfill
data. They cannot be undone by stopping the app.

- **Do not** `git pull` and restart in `/opt/payport/gateway` if that directory serves production (its `.env`
  points at the production `gateway` database). That would be a production deployment, which has not been
  approved.
- Use a **separate checkout** in `/opt/payport/staging` with its **own database** `gateway_staging`, restored
  from a backup (§2), on its **own port** 8085. The production gateway keeps running on 8084, untouched.
- The new build will not start with the old `.env` alone. With no `PAYPORT_ENV` it applies production
  rules and requires `PAYPORT_DATA_KEY`. Staging uses `scripts/staging/env.staging.example`.

## 1. Layout

| | Production (unchanged) | Staging (new) |
|---|---|---|
| Code | `/opt/payport/gateway` (current main build) | `/opt/payport/staging` (new `main`) |
| Database | `gateway` | `gateway_staging` (restored copy) with its own MySQL user |
| Port | 8084 | 8085, not exposed to the internet (VPN or IP allowlist, TLS at the proxy) |
| Settings | `.env` | `.env.staging` (mode 600; different `JWT_SECRET` and `PAYPORT_DATA_KEY`) |
| Start / stop | `./start.sh` / `./stop.sh` | `scripts/staging/start-staging.sh` / `stop-staging.sh` (PID file; never stops production) |
| Frontends | pm2 `payport-admin`, `payport-ops` | separate pm2 apps built with `VITE_API_URL=https://<staging host>` (§3) |

`start-staging.sh` refuses to start unless:
- `PAYPORT_ENV=staging`;
- the database name contains `staging`;
- settlement is `PARALLEL` with automation off;
- the port is not 8084;
- the JWT secret differs from production's;
- the env file is not readable by other users.

## 2. Build the staging database from a real backup (the restore drill)

```bash
sudo mkdir -p /opt/payport/staging && sudo chown $USER /opt/payport/staging
git clone https://github.com/bdube1/payport.git /opt/payport/staging && cd /opt/payport/staging

# Use your backup job's latest file. If you have none, take one read-only:
mysqldump --single-transaction --routines --triggers --no-tablespaces -u payport_admin -p gateway | gzip > ~/gateway-$(date +%F).sql.gz

MYSQL_PWD='<mysql admin password>' scripts/staging/build-staging-db.sh 127.0.0.1 3306 root ~/gateway-YYYY-MM-DD.sql.gz
```

The script:
- restores into `gateway_staging` (it refuses any name without "staging");
- times the restore;
- records row counts, money totals and the backup's SHA-256 in `staging-evidence/`;
- prints the GRANTs for a staging-only MySQL user.

**Personal data.** The copy holds real customer data, and staff password hashes still work. Restrict staging
to the people who may see production data, or mask the copy before wider use. Masking is an owner decision;
the provider sandbox tests use test MSISDNs either way.

After the first staging start (§3), prove that the migrations lost nothing and that the ledger rules hold:

```bash
MYSQL_PWD='<admin password>' scripts/staging/verify-staging-db.sh 127.0.0.1 3306 root gateway_staging
```

It checks:
- no failed migration, and the latest is V52;
- row counts and transaction and legacy-settlement totals are identical to the pre-migration snapshot;
- every journal balances;
- no wallet, float or merchant payable is negative;
- balances equal their lines;
- `ledger_integrity_report()` is empty;
- no settlement batch was instructed.

Exit code 0 means PASS. Rehearsed on 2026-10-22 against MySQL 8.0.46 with a V31 database: it passed, and
it failed as it should when one amount was altered by 1 cent.

Acceptance (restore drill): PASS on a real backup; restore time recorded against RTO ≤ 60 min; the backup's
age recorded against RPO ≤ 5 min.

## 3. Start staging

```bash
cd /opt/payport/staging
cp scripts/staging/env.staging.example .env.staging && chmod 600 .env.staging   # fill it in (test credentials only)
mvn clean package -DskipTests
scripts/staging/start-staging.sh          # first start applies V32-V52 to gateway_staging
tail -f staging.log
```

Staging frontends, one example per app (repeat for the consumer and merchant apps):

```bash
git clone https://github.com/bdube1/payport-ops.git /opt/payport/staging-ops && cd /opt/payport/staging-ops
npm ci && VITE_API_URL=https://<staging api host> npm run build
pm2 start "npx vite preview --port 4001 --host 127.0.0.1" --name payport-ops-staging
```

Add the staging frontend origins to `PAYPORT_CORS_EXTRA_ORIGINS`. Mobile: `EXPO_PUBLIC_API_URL`.

**Access:**
- Staff and customers log in with their existing accounts, because the data is a copy.
- Every product line is off. The owner's switch-on path is in `docs/runbooks/SWITCH_ON.md`; in staging it
  can be practised freely.

## 4. Provider sandbox tests: EcoCash, OneMoney, Econet, e-Solutions

Run from the staging host. OneMoney and Econet are reachable only over their VPNs, so bring the VPNs up on
that host first. Plain HTTP to Econet stays inside the VPN tunnel; that is condition 1 of R76.

```bash
BASE=http://localhost:8085 scripts/staging/provider-sandbox.sh run ECOCASH  AIRTIME_ECONET <test msisdn> 100 USD +263<test payer>
BASE=http://localhost:8085 scripts/staging/provider-sandbox.sh run ONEMONEY AIRTIME_ECONET <test msisdn> 100 USD +263<test payer>
BASE=http://localhost:8085 scripts/staging/provider-sandbox.sh run ECOCASH  ZESA <test meter> 500 USD +263<test payer>
# then, for each run, judged against the provider's test case:
BASE=http://localhost:8085 ADMIN_TOKEN=<admin login> scripts/staging/provider-sandbox.sh record ECOCASH collection pass staging-evidence/sandbox-<ref>.json
```

Each run goes through PayPort's public API (initiate, pay, follow to a final status) and saves the full
timeline. The runner does not judge pass or fail; a person does, against the provider's test case. Then
`record` files the result in the integration register.

| Provider | Test cases to run (minimum) | Acceptance |
|---|---|---|
| EcoCash (payment) | success; customer declines; wrong PIN; timeout (no approval); duplicate callback; status query after timeout | Each outcome matches the provider's test result. No double charge. A timeout is never fulfilled late (refund-only) |
| OneMoney (payment, VPN) | success; insufficient funds; timeout; status query | Same as EcoCash; signatures verified with the platform key |
| Econet (airtime/data delivery, VPN) | USD and ZWG airtime; invalid number; provider fault; delivery timeout then query | Delivered once. Unknown outcomes resolved by query, never resent. Guard (R76) passes genuine responses |
| e-Solutions (ZESA and billers) | token vend; invalid meter; in-progress then resend of the same reference; later lookup | Exactly one token per payment. Resend uses the original reference |

**Done means:** each case is recorded in the register (`SANDBOX`, with evidence). Raising a provider to
`SANDBOX_VERIFIED` in `register.json` is a separate, reviewed change (Phase 5 report §4).

## 5. Staging load test

```bash
BASE=http://localhost:8085 METRICS_TOKEN=<PAYPORT_METRICS_TOKEN> CUSTOMER_TOKEN=<staging customer login> scripts/staging/load-test.sh
```

What it does:
- runs the k6 scenarios (the thresholds are the SLOs);
- samples the HikariCP pool every 5 s (active, idle, waiting, timeouts);
- scans the staging log for pool timeouts, deadlocks and lock waits.

It passes only if k6 meets every threshold and **no** pool or concurrency error occurred.

Investigate every failure the way D10-6 was found:
1. Read the log excerpt.
2. Find the code path holding a connection or lock.
3. Reproduce it in `FinancialIntegrityScenarioTest`, then fix it.
4. Re-run.
5. Record the cause and fix in the Phase 11 report.

Do not "fix" a failure by raising the pool size without finding the cause.

## 6. External penetration test

Arranging this needs the owner: a contract with an independent tester. Send them:
- `docs/phase9/PEN_TEST_SCOPE.md` (scope and rules of engagement);
- the staging URLs, and test accounts per role (customer, merchant, OPS, FINANCE, COMPLIANCE, ADMIN);
- a window, and an emergency contact.

**Test staging only.**

Findings are tracked in the risk register. Critical and high findings are fixed and retested by the tester
before go-live.

Acceptance: final report received; no open critical or high finding; retest letter.

## 7. Settlement parallel run

Run after each legacy settlement cycle, on a staging copy restored after that cycle (§2):

```bash
BASE=http://localhost:8085 FINANCE_TOKEN=<finance login on staging> scripts/staging/settlement-parallel-run.sh USD ZWG
```

The script:
- drafts a PARALLEL batch per currency (the gateway refuses to instruct in PARALLEL mode);
- compares each merchant's new net with what the legacy settlement recorded;
- writes `MATCH`, `AMOUNT_DIFFERS`, `PARTLY_IN_LEGACY` or `NOT_YET_SETTLED_BY_LEGACY` per merchant to a CSV;
- stops if a batch is not in PARALLEL mode.

Every discrepancy is explained in the Phase 11 report: fee rule, cut-off timing, refund, legacy error or
PayPort error.

**No live payout is initiated, and the legacy process is not retired.** Both need the owner's explicit
approval after a clean run of agreed length (proposed: 20 consecutive business days with every discrepancy
explained).

## 8. Phase 11 tracker

| Activity | Tooling | Status | Owner | Acceptance |
|---|---|---|---|---|
| Secure staging environment | §1, §3, staging guard | Ready to run on the owner's server | Owner / ops | Staging up on its own DB, port, secrets; not reachable from the internet except via proxy and allowlist |
| Real-backup restore drill | §2 | Ready; rehearsed locally | Ops / DBA | PASS on a real backup; RTO and RPO recorded |
| Provider sandboxes (EcoCash, OneMoney, Econet, e-Solutions) | §4 | Ready; needs the VPNs and the staging host | Engineering lead | Every case in §4 recorded with evidence |
| Staging load test | §5 | Ready; needs k6 on the host | Engineering lead | k6 thresholds met; zero pool or concurrency errors |
| External penetration test | §6 | Owner to contract | Owner / security | Report, no open critical or high finding, retest |
| Settlement parallel run | §7 | Ready | Finance | Agreed run length; every discrepancy explained |

Production activation, live payouts, LIVE settlement mode and settlement automation all stay **off**.

## Staging addresses (https)

| Address | What | Local port |
|---|---|---|
| https://admin-staging.payport.africa | Ops portal | 4000 |
| https://app-staging.payport.africa | Consumer portal | 4001 |
| https://merchant-staging.payport.africa | Merchant and agent portal | 4002 |
| https://api-staging.payport.africa | Gateway | 8085 |

1. DNS: four A records (admin-staging, app-staging, merchant-staging, api-staging) → the staging server.
2. `sudo EMAIL=<you> scripts/staging/setup-staging-domains.sh` (nginx + Let's Encrypt, renews itself).
3. `.env.staging`: `GATEWAY_BASE_URL`, `OPS_PORTAL_URL`, `CONSUMER_BASE_URL`, `MERCHANT_PORTAL_URL` and
   `FORWARD_HEADERS_STRATEGY=native` as in `env.staging.example`. The three portal addresses are allowed by CORS
   outside production without listing them.
4. Portals: `npm run build -- --mode staging` (reads `.env.staging`: `VITE_API_URL=https://api-staging.payport.africa`).
