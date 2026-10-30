# PayPort Commerce: decision records

## D-001 Commerce runs as a module of the gateway with its own schema (proposed, 30 Oct 2026)
**Context.** The brief asks for a modular monolith, a hard financial boundary and no shared database access.
**Options.** (a) Separate service with its own database; (b) module in the gateway sharing the PSP schema;
(c) module in the gateway with its own schema and database user.
**Recommendation.** (c). One deployment and one CI, like today, while the boundary is enforced by the database
(Commerce's user has no rights on PSP tables) and by an architecture test in the build. Moving Commerce out later
is a deployment change, not a rewrite, because it already talks to the PSP only through `PspPaymentsApi` and events.
**Status.** Waiting for approval.

## D-002 One event outbox for the PSP and Commerce (proposed, 30 Oct 2026)
**Context.** Reporting, merchant webhooks and later AI need one reliable record of what happened.
**Recommendation.** A `platform_events` outbox written in the same transaction as each change; versioned events
with stable IDs; idempotent consumers; merchant webhooks become one consumer. It never replaces the transaction
tables or the ledger.
**Status.** Waiting for approval.

## D-003 AI features start read-only, behind flags, with cost limits (proposed, 30 Oct 2026)
**Context.** The owner wants AI and machine learning embedded now; the brief puts AI at Phase 3 and ML at Phase 4.
**Recommendation.** Build the data foundation and AI guard-rails in Phase 1; start complaint triage, the ops
investigation helper and shadow fraud scoring behind flags; keep the brief's order for commerce AI.
**Status.** Waiting for approval.
