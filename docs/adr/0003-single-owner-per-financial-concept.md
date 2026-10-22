# ADR-0003: One owning module per financial concept

- **Status:** **Accepted** (approved 2026-10-15).

## Context
Refunds, disputes, settlement and reconciliation each exist in both the gateway and the PSP branch.

## Options considered
(a) Keep both and synchronise. (b) The PSP module owns the concept; gateway tables become read models or are migrated, then retired.

## Decision
(b). Each concept has one writer. During migration, dual-write is allowed only through the owning module's API, with a reconciliation check between the old and new records until cut-over.

## Consequences (financial, security, operational)
Removes conflicting truths. Requires an explicit migration and parallel run for settlement and refunds.

## Alternatives rejected
(a) guarantees eventual disagreement on money.

## Revisit when
None.
