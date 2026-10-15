# ADR-0010: Payment and delivery lifecycle under uncertainty

- **Status:** Accepted (Phase 2, 2026-10-16).

## Context
Before Phase 2:
- **The provider was called inside the database transaction.** An exception rolled the payment back to INITIATED, so it could be pushed again while the first push was still on the customer's phone.
- **"Timed out" and "failed" were the same thing.** Every adaptor turned a read timeout or a 5xx into `submitted=false`. The payment was then marked FAILED and the customer received a failure SMS, even though they might have been charged.
- **Callbacks were processed straight from the request.** There was no record of what arrived. A success arriving after a failure was dropped silently.
- **ZimSwitch webhooks were checked for structure only (R45).** A forged body with the success result code marked a card payment paid.
- **The watchdog saved a stale entity.** That could overwrite a confirmation that had just landed.
- **Fulfilment ran from an in-memory queue (R12).** Jobs were lost on restart, could start before the confirmation committed, and a delivery timeout looked the same as a refusal.

## Decision
1. **Commit before calling, record after.**
   - For a payment: PAYMENT_PENDING, a `payment_attempts` row (SENT) and a `payment_checks` row are committed before the provider is called.
   - For a delivery: FULFILMENT_PENDING is committed first.
   - The provider call runs outside any database transaction.
   - The outcome is recorded in a second transaction, which never overwrites a state a callback has already set.
2. **Three outcomes, not two.**
   - ACCEPTED or DELIVERED.
   - REJECTED: the provider definitely did not act. This covers connection refused, unknown host, connect timeout and 4xx responses.
   - UNKNOWN: everything else.

   An UNKNOWN outcome is never failed and never re-sent. It is resolved only by a status query, or by a person. `SubmissionErrors` classifies errors, and adaptors set `outcomeUnknown`.
3. **One row lock per payment.** Submit, callbacks, the resolver, the watchdog and fulfilment all take `SELECT … FOR UPDATE` on the transaction row, then refresh it. Exactly one of several racing events changes the payment. `Transaction` uses dynamic updates, so an UPDATE never rewrites columns that weren't changed. The audit row joins the same database transaction.
4. **Callback inbox (`payment_callbacks`).** Every callback is stored before processing.
   - Identical bodies are one row with a count. Once applied, a body is not processed again.
   - The outcome is recorded: APPLIED, NO_CHANGE, CONFLICT, INVALID or UNKNOWN_REF.
   - A callback that contradicts the recorded outcome (success for a failed payment, failure for a paid one, or a wrong amount) is a **CONFLICT** for a person. It is never applied and never silently dropped.
5. **Per-provider callback trust.**
   - Providers listed in `payport.payments.query-providers` (default ECOCASH, ONEMONEY, ZIMSWITCH) are never trusted on their content. We act only on our own authenticated status query.
   - Other providers must pass the adaptor's signature validation.
6. **Durable resolver (`payment_checks`).**
   - Runs under a JobRunner lease, so only one instance resolves at a time.
   - Polls every 10 s for 30 min, then every 10 min.
   - After 72 h it marks the payment MANUAL. Time never fails a payment.
   - A definite success for the wrong amount goes straight to MANUAL.
   - Payments pending before this release are backfilled.
7. **Durable fulfilment jobs (`fulfilment_jobs`).**
   - A job is written in the confirming transaction and claimed under a lease.
   - If a lease expires mid-delivery, the job becomes UNKNOWN. It is resolved by `queryDelivery`, and becomes MANUAL after 2 h.
   - A payment still at PAYMENT_CONFIRMED was never sent, so its job is queued again.
   - Ops retry is refused while the delivery outcome is unknown.

## Consequences
- **Exactly one financial effect.** This holds under duplicate, out-of-order and early callbacks, timeouts, provider outages and concurrent `/pay` requests. It is tested in `PaymentLifecycleScenarioTest` and `FulfilmentJobScenarioTest` on real MySQL.
- **Fewer false failures, more pending payments.** Customers whose provider timed out are no longer told their payment failed. They see pending until the provider answers.
- **Providers without a delivery query (Econet airtime) go to MANUAL** after an unknown delivery. A person checks; nothing is re-sent.
- **A held row lock costs a pooled connection.** Contention is per payment, so it is small. The audit write no longer needs a second connection.

## Alternatives rejected
- **Optimistic retries with idempotency keys at the provider.** None of the current providers documents idempotent submission.
- **Keeping the call inside the transaction with a shorter timeout.** This still rolls back the evidence that a request was sent.

## Revisit when
- Providers offer idempotency keys or signed callbacks.
- The ops console (Phase 6) needs actions on CONFLICT and MANUAL items, beyond SQL and logs.
