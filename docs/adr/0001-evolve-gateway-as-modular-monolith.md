# ADR-0001: Evolve the existing gateway into a modular monolith

- **Status:** **Accepted** (approved 2026-10-15).

## Context
Payport's gateway is a working Spring Boot/MySQL application with production provider adaptors (EcoCash, OneMoney, O'mari, ZimSwitch, Econet, NetOne, Telecel, e-Solutions). The PSP needs strong financial consistency, and the team is small.

## Options considered
(a) Rewrite as microservices. (b) A new PSP application beside the gateway. (c) Evolve the gateway into a modular monolith with enforced module boundaries.

## Decision
(c). Retain the gateway and its adaptors. Introduce PSP modules with owned tables and package-private internals, plus an architecture test forbidding cross-module repository access. Move gateway code into modules progressively.

## Consequences (financial, security, operational)
Financial: local ACID transactions for posting, with no distributed-transaction failure modes. Security: one attack surface to harden. Operational: one deployable; requires a cluster-safe job framework before scaling out.

## Alternatives rejected
(a) adds network failure modes to money movement with no current scaling need. (b) duplicates provider integrations and creates two sources of truth.

## Revisit when
Independent scaling or deployment cadence needs, separate regulatory perimeters per entity, or team growth beyond about three squads.
