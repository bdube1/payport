# Runbook: ledger operations (Phase 1)

## 1. One-time database setup (DBA)

1. **Create the users.**
   - A **migration user**: full rights on the schema, including CREATE ROUTINE. It runs Flyway and becomes the owner of the ledger procedures.
   - An **application user**, which gets least-privilege grants in step 3.

   Neither user needs SUPER, and `log_bin_trust_function_creators` is not required (the ledger uses procedures, not triggers or functions).
2. **Configure the app** to run migrations as the migration user:
   - `FLYWAY_USER` / `FLYWAY_PASSWORD`: the migration user;
   - `DB_USERNAME` / `DB_PASSWORD`: the application user.
3. **After every release that adds tables**, re-apply the grants:
   ```
   scripts/db/grant-least-privilege.sh <host> <port> <dba_user> <dba_password> <database> <app_user> [app_user_host]
   ```
   Add `--print` to review the SQL first. The app user then has DML on gateway tables, SELECT on `ledger_*` and `ref_*`, and EXECUTE on the public ledger procedures only.

## 2. Turning on shadow posting (observe only)

1. Set `LEDGER_SHADOW_ENABLED=true` and restart. Every minute, one instance (whichever holds the lease) posts new gateway activity to the ledger. Payments are not affected.
2. **Backfill history once** as an ADMIN user:
   ```
   POST /api/v1/admin/ledger/shadow/backfill?from=YYYY-MM-DD
   ```
   It is idempotent, so running it again posts nothing twice.
3. **Review daily:**
   - `GET /api/v1/admin/ledger/shadow/comparison?differencesOnly=true`: every row needs an explanation.
   - `GET /api/v1/admin/ledger/shadow/failures`: postings the ledger refused. Each has a stable code, for example `LEDGER_INSUFFICIENT_FUNDS` (refund after settlement) or `LEDGER_PERIOD_CLOSED`.
4. **Resolving failures.**
   - Failures are retried automatically on every run until they post.
   - One that will never post (a known legacy gap) is closed with an explanation:
     `POST /api/v1/admin/ledger/shadow/failures/resolve {"idempotencyKey": "...", "resolution": "..."}`

To turn shadow posting off, set `LEDGER_SHADOW_ENABLED=false`. Nothing else changes.

## 3. Integrity and tamper evidence

- **Nightly (02:15):**
  - entries older than 2 minutes are sealed into the hash chain;
  - every integrity check runs, and each violation is logged at ERROR as `[LEDGER-INTEGRITY] <code> <subject> <detail>`.
- **Alerting:** alert on any `[LEDGER-INTEGRITY]` line, and on a failed `ledger-seal-and-integrity` run.
- **On demand:**
  - `GET /api/v1/admin/ledger/integrity`;
  - `POST /api/v1/admin/ledger/integrity/run`.
- **External anchor:** export the newest row of `ledger_seals` (`id`, `to_entry_id`, `seal_hash`) every day to storage PayPort admins cannot rewrite (WORM bucket or SIEM). Without the anchor, a DBA could rebuild the whole chain.

| Violation code | Meaning | Treat as |
|---|---|---|
| `UNBALANCED_ENTRY`, `TOO_FEW_LINES`, `LINE_CURRENCY_MISMATCH` | Journal rows altered outside the procedures | **P1 incident** (possible tampering) |
| `BALANCE_MISMATCH`, `NEGATIVE_PROTECTED` | Projection altered or corrupted | P1; the projection can be recomputed from lines by a DBA under change control |
| `CONTENT_HASH_MISMATCH`, `SEAL_MISMATCH`, `SEAL_CHAIN_BROKEN` | Posted history changed | **P1 incident**: preserve evidence and follow the incident runbook |
| `REVERSAL_NOT_MIRROR` | Reversal lines altered | P1 |

## 4. Periods

Period close and re-open exist in the database (`ledger_close_period`, `ledger_reopen_period`) and in `LedgerClient`. **No API endpoint exposes them yet** (Phase 3, with month-end close).
- **Until then:** an engineer runs them under a change ticket, and re-opening needs a recorded second approval.
- **While a month is closed:** postings dated in it are refused (`LEDGER_PERIOD_CLOSED`) and appear as shadow failures until the month is re-opened.

## 5. Tax rates

- **Enter:** finance enters the rates confirmed by the tax adviser with `POST /api/v1/admin/ledger/tax-rates`. The rate is PROPOSED.
- **Approve:** a **different** FINANCE or ADMIN user approves it with `POST /api/v1/admin/ledger/tax-rates/{id}/approve`. The database refuses self-approval.
- **Change:** a new rate must start after the current one. The current rate is then closed the day before, so history is never rewritten.

## 6. Scheduled-job history

Every run of a ledger job is in `platform_job_runs`. A run skipped because another instance held the lease is normal and is not recorded as a failure.
