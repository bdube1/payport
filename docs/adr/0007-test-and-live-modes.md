# ADR-0007: Test mode and live mode as a first-class attribute

- **Status:** **Accepted** (approved 2026-10-15).

## Context
Critical defect R1: a session without an API key is treated as test mode and simulated as paid, yet it remains settleable.

## Options considered
(a) Patch the default. (b) Make livemode an explicit attribute of credentials, sessions, payments, ledger books and webhooks; test-mode money can never be settled.

## Decision
(b), with (a) as immediate containment in Phase 0.5.

## Consequences (financial, security, operational)
Eliminates a class of 'fake money becomes real' defects.

## Alternatives rejected
(a) alone leaves the ambiguity in place.

## Revisit when
None.
