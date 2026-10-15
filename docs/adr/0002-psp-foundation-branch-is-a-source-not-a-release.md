# ADR-0002: Treat the psp-foundation branch as a source of designs, not a release

- **Status:** Proposed (Phase 0, 2026-10-15). Awaiting approval.

## Context
The branch adds about 19k lines, compile-checked only against stub libraries and never run. It wires merchant gates, routing and fraud blocking into the live checkout path, and it includes products (wallets, agents, QR, USSD, WhatsApp, FX) whose licensing is unresolved. Its migrations do apply cleanly on MySQL 8.0.46, apart from the trigger privilege issue.

## Options considered
(a) Merge and deploy after a successful build. (b) Discard. (c) Harvest module by module into the phased plan, with tests and feature flags.

## Decision
(c). Nothing from the branch reaches production except through a phase's acceptance gate. Live-path changes ship behind flags in observe or shadow mode first. Out-of-phase products stay parked.

## Consequences (financial, security, operational)
Avoids deploying unverified money code. Keeps the useful designs (ledger, outbox, approvals, callback inbox, resolver, settlement engine). Costs rework time.

## Alternatives rejected
(a) violates the verification rules and risks live payments. (b) wastes a sound ledger and lifecycle design and a validated schema.

## Revisit when
None expected.
