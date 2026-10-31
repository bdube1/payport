# Phase 1: Financial foundation, delivery report

_Branch `phase1-financial-foundation` (on top of `phase0.5-containment`). 2026-10-15._

## 1. What was built

| Component | Where | Purpose |
|---|---|---|
| Reference data | V33: `ref_currencies`, `ref_countries`, `ref_legal_entities` | Multi-country from day one. ZW, USD and ZWG are seeded; other markets are added as data (ADR-0006). |
| Chart of accounts | V33: `ledger_categories` | 16 categories with type, normal side and no-negative rule. **Proposal awaiting finance sign-off.** |
| Ledger tables | V33: `ledger_accounts`, `ledger_balances`, `ledger_periods`, `ledger_entries`, `ledger_lines`, `ledger_seals` | Double-entry journal; LIVE and TEST books; idempotency key per business event. |
| Write path | V34: definer procedures `ledger_post`, `ledger_reverse`, `ledger_open_account`, `ledger_close_period`, `ledger_reopen_period`, `ledger_seal`, `ledger_integrity_report` | The only way to write the ledger. The database enforces balance per currency, no-overdraft, open periods, mirror-image reversals and idempotency (ADR-0004, ADR-0008). |
| Least privilege | `scripts/db/grant-least-privilege.sh` | App user: DML on gateway tables, SELECT on ledger tables, EXECUTE on public procedures only. |
| Java ledger client | `gateway/ledger/*` | Plain JDBC; its own short transactions; retries lock conflicts; never joins a payment transaction. |
| Posting templates | `ledger/templates/PostingTemplates` | Eight versioned templates. Merchant figures reproduce the legacy arithmetic exactly. |
| Shadow posting | `ledger/shadow/ShadowLedgerSweeper`, V35 | Observe-only (ADR-0009): scans gateway rows, posts once, records and retries failures. Includes backfill. |
| Comparison | `ledger/shadow/ShadowComparison` | Ledger vs legacy, per merchant and currency (exit criterion E5). |
| Job leases + history | `platform/JobRunner`, V36 | Scheduled financial work runs on one instance at a time; every run is recorded. |
| Maker-checker | `platform/ApprovalStore`, V36 | The database refuses self-approval; role checks; expiry. |
| Tax rates | `billing/TaxRateService`, V36 | Effective-dated VAT and IMTT, active only after a second person approves. **None seeded.** |
| Scheduled jobs | `ledger/LedgerJobs` | Shadow sweep (flag); nightly seal and integrity check that logs violations at ERROR. |
| Finance / admin API | `GET/POST /api/v1/admin/ledger/**` | Balances, comparison, failures, backfill, integrity, tax rates. |
| CI | `.github/workflows/ci.yml` job `ledger-db` | Runs the real-database tests exactly as here. |

## 2. Evidence (executed in this environment)

All database tests ran against **MySQL 8.0.46**:
- binary logging on;
- migrations applied by a separate **non-SUPER** migration user;
- the app connected as the **least-privilege** user;
- **real JUnit 5.10**, real JDBC.

The ledger, shadow and platform code under test is plain JDK and JDBC, with no stand-ins anywhere in that path.

| Exit criterion | Result | Evidence |
|---|---|---|
| **E1** 50+ scenarios balance; balances equal the sum of lines | **Pass**: 55 scenarios | `LedgerScenarioTest`. Covers every rejection code, idempotent replay and conflict, closed periods, reversals (single, double, of a reversal, overdrawing), test-book isolation, 100-line entries, maximum amounts, escaping, and a 300-step random sequence ending with a clean integrity report. |
| **E2** 200 parallel debits never overdraw or double-post | **Pass** | `LedgerConcurrencyTest`: exactly 100 of 200 spends succeed against funds for 100, final balance 0. 50 threads with the same key produce one posting. 200 opposing transfers: no deadlock, money conserved. |
| **E3** Interrupted posting leaves nothing | **Pass** | `LedgerAtomicityTest`: connection killed after the procedure ran but before commit; rollback after a successful call; rejection after locks were taken. |
| **E4** App user cannot TRUNCATE, ALTER, DROP or write ledger rows | **Pass** | `LedgerPrivilegeTest`: 19 bypass attempts denied, including calling the internal procedure. The app user holds no TRIGGER, DROP, ALTER, ROUTINE or SUPER privilege. |
| Integrity detector catches tampering outside the procedures | **Pass** | `LedgerIntegrityTest`: changed amounts (detected three ways), a tampered projection, a negative protected balance, a back-dated entry, a forged content hash (caught by the seal), and a broken seal chain. |
| Shadow posting agrees with legacy | **Pass** on scenario data | `ShadowLedgerTest`: collection, two awkward-rounding refunds, settlement created and paid all match legacy at every step; test-mode isolation; idempotent rescans; consumer airtime with commission; refund-after-settlement captured; failure retried after its cause was fixed. |
| Templates reproduce legacy arithmetic | **Pass** | `PostingTemplatesTest`: 100,000 random cases identical to `SettlementService`'s expression; 20,000 random refunds balance and take exactly the legacy reduction. |
| Platform services | **Pass** | `PlatformDbTest`: lease exclusivity and crash takeover; four-eyes enforced in code **and** database; tax rates effective-dated with no history rewrite; failed approvals roll back entirely. |
| Upgrade path | **Pass** | V33–V36 applied on top of a database already holding gateway data (V1–V32). |
| **E5** Shadow matches legacy for a week of production-copy data | **Not yet run.** Needs a production data copy. | The tooling is ready (backfill + comparison). On the Phase 0.5 fixture data it reported merchant 1 **legacy 1,300 vs ledger 600**: the 700 difference is exactly the simulated payment-link session that legacy settlement paid out (R1). |
| **E6** Supported Spring Boot line | **Not done.** Needs CI. | Upgrading blind is unsafe. |

**Totals in this environment:**
- 115 / 115 database and template tests, passed twice on a fresh database;
- 31 / 31 Phase 0.5 unit tests;
- 34 changed main-source files compile with real Lombok (Spring, JPA and Jackson as API stubs).

**Not verified here:**
- `mvn verify`;
- Spring wiring (beans, scheduling, controller, security annotations);
- application start-up;
- the Testcontainers migration test.

All four run in CI.

## 3. Defects found and fixed by testing against the real database

1. **Concurrent spends could overdraw a merchant (critical).** Under REPEATABLE READ, the no-negative check read the transaction's snapshot rather than the row it had just locked: 163 of 200 debits passed against funds for 100. Fixed with a locking read; covered by `LedgerConcurrencyTest`.
2. **Opposing transfers deadlocked.**
   - Accounts were opened inside the posting transaction, so account rows were locked in line order. Fixed by ensuring accounts first, in sorted order, in their own short transactions.
   - The set-based `FOR UPDATE` intended to lock balances in id order was driven from the line order by the optimizer. Replaced with an explicit ordered cursor.
3. Two procedure and test defects found on first execution:
   - `LIMIT` with an expression;
   - `JSON_UNQUOTE` turning JSON null into the string `'null'`.

Each of these would have passed a stub-based compile check.

## 4. Findings about the existing business that the ledger exposes

| Finding | Evidence | Action |
|---|---|---|
| **Refunds recorded after a settlement are never recovered from the merchant.** Legacy ignores settled sessions when computing what is owed. | `ShadowLedgerTest.refundAfterSettlement…`: the ledger refuses to overdraw and records the refund as a failure. | Business decision: claw back from the next settlement, or treat as a loss (approved write-off). |
| **Customer refunds have no payout record.** Gateway refunds are marked COMPLETED immediately, but nothing records the money reaching the customer. | `CUSTOMER_REFUND_PAYABLE` grows and never decreases. | Phase 3: refund payout tracking. |
| **R1 overpayments show up automatically.** | Fixture backfill above. | Run the backfill on a production copy; the difference rows are the exposure. |

## 5. Decisions still needed from you

| # | Decision | Default until decided |
|---|---|---|
| 1 | **Chart of accounts sign-off** (finance lead): categories, names and no-negative rules | Proposal as seeded in V33 |
| 2 | **Is Payport's commission VAT-inclusive?** (tax adviser) | Template v1 does not split VAT; v2 will add it |
| 3 | **Who bears IMTT on Payport's flows** (tax adviser) | Not posted |
| 4 | **Legal name of the entity** and the ledger codes of the real settlement bank accounts | `PAYPORT_ZW`, `SETTLEMENT_USD`, `SETTLEMENT_ZWG` |
| 5 | **Vendor commission rates** (Econet, NetOne, Telecel, e-Solutions) | 0, so no commission revenue is recorded |
| 6 | **Refund-after-settlement policy** (finding above) | Recorded as shadow failures for review |

## 6. Phase 1 status

**Code complete; exit evidence partial.**
- E1–E4 are met with evidence.
- E5 needs a production data copy.
- E6 and every Spring-level check need CI.

Recommendation for E5 and E6:
1. Push the repository so CI runs.
2. Deploy to staging with `LEDGER_SHADOW_ENABLED=true` against a production data copy.
3. Backfill.
4. Review the comparison daily for a week. Every difference needs an explanation before Phase 3 moves settlement onto the ledger.
