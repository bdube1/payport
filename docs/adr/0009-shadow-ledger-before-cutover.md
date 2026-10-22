# ADR-0009: Shadow ledger before any behaviour change

- **Status:** Accepted (Phase 1, 2026-10-15).

## Context
The gateway moves real money today. The ledger must prove it agrees with existing figures before anything depends on it.

## Decision
1. Phase 1 posts gateway events to the ledger in **observe-only** mode: behind a flag, after the gateway's transaction commits, on a separate thread, and never failing or slowing the payment path. Failures are recorded for review.
2. Test-mode activity posts to a separate `TEST` book.
3. A comparison report shows ledger against legacy figures per merchant and currency.
4. The legacy settlement and balances stay authoritative until a later phase switches them, after a week or more of explained agreement.

## Consequences
- No customer-visible risk.
- The ledger can lag the gateway for a few seconds.
- Events lost while the app is down are recovered by the backfill command, which is idempotent.

## Alternatives rejected
Posting inside the payment transaction now. That couples live payments to an unproven component.

## Revisit when
The comparison report has been clean for the agreed period, and Phase 3 moves settlement onto the ledger.
