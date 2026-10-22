# ADR-0005: Canonical payment model with ISO 20022 as a mapping layer

- **Status:** **Accepted** (approved 2026-10-15).

## Context
ISO 20022 interoperability is required, but most PayPort flows (mobile money, billers) have no ISO counterpart. Target scheme versions (ZIPSS, sponsor banks, PAPSS) are not yet confirmed.

## Options considered
(a) Use ISO 20022 messages as the internal model. (b) A PayPort canonical model informed by ISO 20022 business components, with versioned mappers and separate transport.

## Decision
(b). Message versions are chosen per integration only after its implementation guide is obtained. Originals are retained with full correlation to the ledger.

## Consequences (financial, security, operational)
Keeps the core independent of any scheme. Mapping and conformance tests are required per integration.

## Alternatives rejected
(a) forces every flow into scheme-specific structures and versions.

## Revisit when
A national mandate to use ISO 20022 internally for regulatory reporting.
