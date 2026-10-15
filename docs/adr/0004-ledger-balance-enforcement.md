# ADR-0004: Where ledger balance is enforced

- **Status:** Proposed (Phase 0, 2026-10-15). Awaiting approval.

## Context
Tested on MySQL 8.0.46: the database accepts an unbalanced journal line. Balance is enforced only in LedgerService. MySQL has no deferred constraints.

## Options considered
(a) Application enforcement plus a continuous detector job and alerting. (b) Store per-entry totals and lock the entry; a trigger rejects any line insert that makes totals exceed a declared entry total, and a final 'seal' insert asserts debits = credits. (c) A stored procedure is the only posting path, and the app user has no direct INSERT on lines.

## Decision
Proposed: (c) for production (posting through a stored procedure that validates balance and inserts atomically; the app user has EXECUTE only on ledger tables), with (a) retained as independent detection. Decision pending your approval in Phase 1 design.

## Consequences (financial, security, operational)
Financial: a coding bug cannot persist an unbalanced entry. Operational: more DBA-owned code; procedure changes need a migration.

## Alternatives rejected
(a) alone allows a bug to persist bad entries until detection. (b) is complex, with trigger ordering subtleties.

## Revisit when
Move to a database with deferred constraints (e.g. PostgreSQL) or to a dedicated ledger store.
