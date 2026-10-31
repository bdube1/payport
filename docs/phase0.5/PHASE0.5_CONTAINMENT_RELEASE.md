# Phase 0.5: containment release (branch `phase0.5-containment`)

Built on `main` (the gateway as deployed), **not** on `psp-foundation`, so it can ship on its own. It contains only containment fixes for R1–R4 and R6, plus the tooling the remaining Phase 0.5 items need.

## 1. What changes, and who notices

| Risk | Change | Visible to |
|---|---|---|
| **R1** Payment links and dashboard checkouts were simulated as paid | • Every checkout session stores `livemode`, decided once at creation: a LIVE key means live, a TEST key means test, and **no key (payment link, dashboard) means live**.<br>• The hosted page can no longer influence it.<br>• Settlement and the merchant balance and charts include **live sessions only**.<br>• Webhooks carry `"livemode": true/false`.<br>• Merchant and admin session views show `livemode`. | **Merchants:** payment links now charge customers for real (as intended). Test-mode payments drop out of balances and settlement. |
| **R2** Anyone could read applications and upload KYB documents by guessing `MER-…` references | • `GET /api/v1/merchant-portal/track/{ref}` returns only `status` plus a "log in" message.<br>• The two public POSTs (upload document, resolve note) return **410 LOGIN_REQUIRED**.<br>• Applicants use the existing logged-in endpoints `/api/v1/merchant/application` and `/api/v1/merchant/documents`. | **Applicants / merchant-portal frontend** |
| **R3** Public, guessable transaction lookups exposed phone numbers, recipients, the audit timeline and **ZESA tokens** | • `POST /api/v1/transactions/initiate` returns a one-time `accessToken` (only its SHA-256 hash is stored).<br>• `GET /{ref}`, `GET /{ref}/timeline`, `POST /pay` and `POST /{ref}/confirm-omari-otp` give the full view only with header `X-Transaction-Token`, to the logged-in owning customer, or to staff.<br>• Everyone else gets a **public view**: status, amount, product, timestamps, card-widget and OTP fields; **no** phone, recipient, provider references, ZESA token or timeline.<br>• Flag `payport.security.transaction-token.enforce` (env `TRANSACTION_TOKEN_ENFORCE`, default `false`): `true` returns 404 to anyone without access.<br>• The hosted checkout `GET /api/v1/checkout/{id}` now masks the customer phone (`+263******567`). | **Consumer app:** the success screen no longer shows the ZESA token until the app sends the header (customers still receive it by SMS).<br>**Hosted checkout:** phone is masked. |
| **R3** (related) | `/my-history` no longer logs every request header (it was logging cookies and other headers at INFO). Page size is capped at 100. | — |
| **R4** Forged EcoCash/OneMoney callbacks | • The callback content is no longer trusted. On any EcoCash or OneMoney callback, Payport **queries the provider** and applies only a definite answer.<br>• If the provider reports an amount, it must match.<br>• A callback that contradicts the provider is logged as `CONTRADICTION`. | **Ops:** new log lines `[VERIFY]`, `[CALLBACK] CONTRADICTION` |
| **R6** EcoCash payments were marked FAILED after 30 minutes | • The reconciler now covers EcoCash **and OneMoney** and never fails a payment because time passed.<br>• Back-off: every 10 s for 30 min, then every 10 min, then stop at 72 h with a single summary log for manual resolution. | **Ops:** stuck payments stay `PAYMENT_TIMEOUT` until resolved, instead of becoming FAILED |

## 2. Frontend work needed (repositories not available to me)

1. **Consumer app:**
   - store `accessToken` from the `/initiate` response;
   - send it as `X-Transaction-Token` on every later call for that reference (`/pay`, polling `GET /{ref}`, `/timeline`, `/confirm-omari-otp`).

   After that ships, set `TRANSACTION_TOKEN_ENFORCE=true`.
2. **Merchant portal tracking page:** replace upload and resolve on the public page with a "Log in to continue" link. The logged-in pages already exist.
3. **Hosted checkout:** don't prefill the phone field from `customerPhone` (it is now masked).
4. **Merchant integrations / docs:** tell merchants about the new webhook field `livemode`.

## 3. Deployment

1. Take a backup, and check that it restores, with `scripts/phase0.5/restore-drill.sh` on a non-production server.
2. Run the schema drift check against a structure-only dump of production (`scripts/phase0.5/schema-diff.sh`). Investigate any drift before deploying, because V32 alters `checkout_sessions` and `transactions`.
3. Deploy. Flyway applies **V32** (no triggers, so no special DB privileges are needed).
4. Run `scripts/phase0.5/r1-exposure-report.sql` (read-only) against production and send the output to finance.
   - Section 1: simulated "paid" sessions per merchant, and how much of that was settled.
   - Section 2: the payment-link cases in detail.
   - Section 3 must return 0 rows.
5. Watch the logs for `[VERIFY]`, `CONTRADICTION` and the `[RECONCILE]` 72-hour summary.

**Rollback:**
- **Code:** redeploy the previous JAR, **and first run**
  `ALTER TABLE checkout_sessions MODIFY livemode BOOLEAN NULL;`
  The old code does not set `livemode`, so without this statement every new checkout session would fail to insert.
- **Schema:** optionally, the full DOWN statements at the end of V32.

Note that rolling back also brings back R1–R4. To redeploy this release after such a rollback, first run `scripts/phase0.5/v32-rebackfill-after-rollback.sql`, which classifies the sessions created meanwhile and restores NOT NULL (tested).

## 4. Evidence (what was actually executed)

| Check | Result | How |
|---|---|---|
| 16 changed/new main files compile with full method bodies | **Pass** | `javac` with **real Lombok 1.18.24** (Ubuntu package); Spring/JPA/Jackson from **API stubs** (Maven Central still blocked) |
| Unit tests: livemode rule, settlement eligibility, access decisions, phone matching, token hashing, verifier rules, amount conversion, reconciler back-off, phone masking, public view redaction | **31 / 31 pass** | **Real JUnit 5.10.1** console launcher; tested classes compiled from real source |
| V32 on MySQL 8.0.46 after V1–V31, binlog on, non-SUPER user | **Pass** | Applied with the mysql client |
| V32 backfill on six historical cases (live key, test key, simulated payment link, real no-key payment, unpaid link, unpaid test session) | **All correct** | Fixture data, then inspected |
| Session insert without a mode | **Rejected** by the DB (`livemode` NOT NULL, no default) | — |
| Exposure report | Correctly isolates the simulated, settled payment-link session; check section returns 0 rows | — |
| `apply-migrations.sh`, `schema-diff.sh` (incl. drift detection), `restore-drill.sh` | **Work** against local MySQL | — |
| `LivemodeMigrationTest` (Testcontainers + real Flyway) | **Not run**: no Docker daemon or Flyway/Testcontainers jars here. **First run will be in CI.** | — |
| Full `mvn verify`, application start, end-to-end HTTP flows | **Not run** | Blocked on Maven access |

## 5. Phase 0.5 acceptance criteria (from doc 08)

| Criterion | Status |
|---|---|
| Payment link in live mode calls the provider; never `TEST_SIM_` | Rule implemented and unit-tested; **end-to-end not yet run** |
| A test-mode session is never selected by settlement | Unit-tested and DB-enforced (NOT NULL + backfill on real MySQL) |
| Unauthenticated tracking access rejected | Implemented as status-only GET + 410 on writes (the authenticated endpoints already existed, so no new token was needed) |
| Transaction lookup without token returns minimal body or 404 | Implemented and unit-tested (public view; 404 when enforced) |
| CI green on the gateway | **Pending:** workflow written; needs the repository on GitHub (or your CI) |
| Restore drill timed and recorded | **Pending:** script ready and tested locally; needs a real backup |
| Production schema diff (D1) | **Pending:** script ready and tested; needs the structure-only dump |

## 6. Known limitations of this containment

- **Single instance assumed.** The reconciler's back-off clock is in memory; durable jobs come in Phase 1/2.
- **Callback races.** Concurrent callback and reconciler can both reach `handlePaymentCallback`. Its status guard makes the second one a no-op in normal timing, but there is no row lock (Phase 2).
- **Enumerable checkout ids.** Hosted checkout ids are still sequential numbers. Opaque ids need a URL change coordinated with merchants (Phase 4).
- **Timeout watchdog.** The watchdog still changes status without an audit entry (Phase 2).
- **Telecash** callbacks have no adaptor and are acknowledged and ignored, as before.
