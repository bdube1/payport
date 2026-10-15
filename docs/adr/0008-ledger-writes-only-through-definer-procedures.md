# ADR-0008: Ledger writes only through definer stored procedures

- **Status:** Accepted (Phase 1, 2026-10-15), implementing ADR-0004 option (c).

## Context
Phase 0 tested the earlier trigger-based design on MySQL 8.0.46:
- triggers need SUPER or `log_bin_trust_function_creators` when binlog is on, so migrations half-applied;
- an app user with ordinary `ALL` grants could `TRUNCATE` the journal or drop the triggers;
- the database accepted unbalanced entries.

## Decision
1. Journal tables (`ledger_entries`, `ledger_lines`, `ledger_balances`, `ledger_seals`, `ledger_periods`, `ledger_accounts`) are written **only** by `SQL SECURITY DEFINER` procedures.
2. The procedures validate structure, accounts, period, per-currency balance and no-overdraft, lock balance rows in account-id order, and refuse to run with autocommit on.
3. The application database user is granted EXECUTE on the procedures and SELECT on those tables. It has no INSERT, UPDATE, DELETE, DROP or ALTER on them.
4. Migrations run as a separate migration user, and the procedures' definer is that user.
5. If an environment still runs migrations as the app user, nothing breaks, but the protection reduces to what the procedures themselves check. `scripts/db/grant-least-privilege.sh` sets up the split.

## Consequences
- A coding error in Java cannot persist an unbalanced or overdrawn entry, and cannot alter or delete history.
- Posting logic lives partly in SQL. Every procedure change is a new migration, and the logic is tested against real MySQL in CI and here.
- Throughput: balance rows are row-locked only for the accounts an entry touches. The hash chain is built by a separate sealing step, so postings are not serialised.

## Alternatives rejected
- **Triggers** (privilege fragility, bypassable).
- **Application-only checks** (no defence against bugs).
- **A separate ledger database product** (premature).

## Revisit when
Posting volume needs sharding, or the platform moves to a database with deferred constraints.
