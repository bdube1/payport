# Phase 0: Database and migration assessment

Test evidence is in report 05 §5. This document assesses the data model.

## Current state [Observed]

- **Engine:** MySQL 8 (InnoDB). Spring Data JPA / Hibernate. Flyway V1–V31 on the gateway; the branch adds V32–V40.
- **Gateway model:** about 32 tables. `transactions` carries one status mixing payment and fulfilment. `checkout_sessions` carries merchant money fields: commission, net and `settlement_id`. `settlements` holds legacy payouts. Separate refund, dispute, webhook, reconciliation and statement tables.
- **Money:** integer cents (`*_cents BIGINT`), which is correct. One exception: legacy settlement pro-rating converts through `double`.
- **Transactions:** service-level `@Transactional`. No row locking around merchant balances, because there are no balances.
- **Schema control:** Flyway plus `ddl-auto=update`. Hibernate may have added columns in production that no migration records. **Production may therefore differ from V1–V31.**
- **Branch model:** 48 PSP tables (`psp_*`). They are generated from spec files so entities and DDL agree, migrate cleanly on MySQL 8.0.46, and the upgrade path equals a clean install (both tested).

## Findings

| # | Finding | Severity | Recommendation |
|---|---|---|---|
| D1 | Production schema may have drifted (`ddl-auto=update`) | High | Before any PSP migration: dump the production schema (no data), diff it against V1–V31, and add a reconciling migration. Then switch to `validate`. |
| D2 | Trigger migrations need `log_bin_trust_function_creators` or SUPER; otherwise Flyway half-applies V32/V33 | High | Precondition check that fails fast before any DDL; triggers in a separately-owned script |
| D3 | App DB user can TRUNCATE or DROP TRIGGER | High | Separate users: `payport_migrate` (DDL) and `payport_app` (SELECT/INSERT/UPDATE/DELETE on named tables; no DELETE/UPDATE on ledger tables at all) |
| D4 | Ledger balance enforced only in application code | Medium | ADR-0004 |
| D5 | Currency CHECK fixed to `USD`, `ZWG` | Medium | Reference table `currencies` (code, minor units, enabled per country), with an FK from ledger accounts |
| D6 | Two models for the same concepts (refund, dispute, settlement, reconciliation) | Medium | ADR-0003 |
| D7 | No partitioning or retention plan for append-only tables (journal lines, audit, callback inbox, outbox) | Medium | Phase 9: monthly partitioning of journal lines and audit; archival policy aligned with retention law |
| D8 | No tested backup/restore yet | High | Phase 1 exit criterion: restore drill with evidence |
| D9 | Personal data spread across many gateway tables (phones, emails, names, IDs) | Medium | Data inventory for CDPA; field encryption where justified |
| D10 | Flyway `repair-on-migrate` in properties is not a real Spring property (removed on the branch) | Low | — |

## Migration strategy (proposed)

1. **Never edit an applied migration.** The branch's V32–V40 have not been applied anywhere real, so they may still be **reworked before their first production application**. I recommend that, to fix D2, D3 and D5, rather than layering corrective migrations on top.
2. **Expand, migrate, contract.** Add new structures alongside old ones, backfill and dual-write, switch reads, then retire. Each step is reversible until "contract".
3. **Each release is tested three ways:**
   - (a) a clean database;
   - (b) a copy of the production schema;
   - (c) an anonymised production data copy, with row counts and financial totals compared before and after.
4. **Down paths.** Every migration states its rollback, either a script or a reason why rollback is a restore.
