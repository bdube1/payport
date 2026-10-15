# ADR-0006: Country, legal entity and currency as reference data with versioned policy sets

- **Status:** Proposed (Phase 0, 2026-10-15). Awaiting approval.

## Context
Zimbabwe assumptions are hard-coded in 74 places, and a CHECK constraint restricts the ledger to USD and ZWG. The platform must expand to other markets without rewriting the core.

## Options considered
(a) Feature flags and conditionals. (b) Reference tables (countries, currencies, legal entities, country products, calendars) plus versioned, approved policy sets, with CountryPolicy implementations only where configuration is insufficient.

## Decision
(b). Every payment and posting records the policy-set version used.

## Consequences (financial, security, operational)
Adds configuration governance (approval and audit of policy changes). Enables new markets through configuration and adapters.

## Alternatives rejected
(a) scatters country logic through business code.

## Revisit when
None.
