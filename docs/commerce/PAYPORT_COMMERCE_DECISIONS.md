# PayPort Commerce: decision records

## D-001 Commerce is its own service with its own database (decided by the owner, 31 Oct 2026)
**Context.** Phase 0 proposed a module inside the gateway. The owner decided: "PayPort Commerce must be able to operate
independently of PayPort Payments. If the PSP goes down, Commerce must remain available."
**Decision.** A separate Spring Boot service (`commerce/` in this repository, its own build and CI workflow), its own
database and database user (no rights on PSP tables), its own port (8086) and address (store.payport.africa).
Pages render from Commerce's own data; paying needs the PSP and is retried by the customer when the PSP is back.
Payment results come from the PSP's event stream, read with a cursor, and are checked against the PSP's status.
**Status.** Accepted and built (Phase 1).

## D-002 One event stream for the PSP (accepted, 31 Oct 2026)
`platform_events`, written when the change happens; one event per state change (unique dedupe key); versioned; no
personal data in payloads; read in order through `/api/v1/commerce/events`. Merchant webhooks keep their own outbox for
now (moving them onto the stream is a later, separate change).
**Status.** Accepted and built.

## D-003 AI features start read-only, behind switches (accepted, 31 Oct 2026)
**Status.** Accepted. Complaint triage, the investigation helper and shadow fraud scoring start behind switches; store
AI stays in Phase 3.

## D-004 Merchant refunds (decided by the owner, 31 Oct 2026)
"A merchant can refund its own customer from its own balance." The refund is allowed while PayPort still holds the
payment for the merchant (not yet paid out), reduces the merchant's balance at once, and the payout to the customer is
listed in the central reversal register (MERCHANT_REFUND, awaiting payout) for Finance to pay and record.
**Status.** Accepted and built. A refund of a payment already paid out to the merchant (to be recovered from the next
settlement) is not built; it needs a settlement adjustment and is a separate decision.

## D-005 Storefront address and server (accepted, 31 Oct 2026)
`store.payport.africa` (`store-staging.payport.africa` on staging) until custom domains (Phase 2b). A second server for
monitoring and Commerce staging is agreed.
