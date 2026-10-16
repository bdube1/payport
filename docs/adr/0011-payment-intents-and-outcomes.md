# ADR-0011: Payment intents, attempts and on-the-spot outcomes

- **Status:** Accepted (owner direction, 2026-10-16: "lets copy stripe").

## Context
- 72 hours of "pending" is too long.
- USSD and other one-session channels must tell the customer, in the same session, that the payment SUCCEEDED, FAILED, TIMED OUT or got NO RESPONSE.
- SMS are expensive.
- Customers must see the provider's own failure text.

## Decision
1. **A transaction is a payment intent** (Stripe's PaymentIntent).
   - Each request to a provider is an attempt (`payment_attempts`).
   - Attempt 1 is sent with the transaction reference. Attempt N is sent with `reference-N`, because providers reject a reused reference.
   - A new attempt is allowed only after a **definite** failure, up to `PAYMENT_MAX_ATTEMPTS` (3).
   - A late success for a superseded attempt is a reconciliation exception. It is never applied to the current attempt.
2. **Payment window per provider.** The default is 180 s; ZimSwitch is 30 min (its checkout lifetime); O'mari is 5 min (its OTP validity). When the window closes:
   - the provider is asked once more;
   - a definite answer is applied;
   - otherwise the payment becomes PAYMENT_TIMEOUT with outcome TIMED_OUT (the provider answered but the customer did not complete) or NO_RESPONSE (the provider never answered).
3. **Late payments are never fulfilled** (owner rule, 2026-10-16). Once the customer has been told TIMED_OUT or NO_RESPONSE, the payment can never be confirmed: there is no transition from PAYMENT_TIMEOUT to PAYMENT_CONFIRMED, and no approval that can override it. A success that arrives later is a `PAID_AFTER_TIMEOUT` exception, which can only be resolved as a refund. Checks continue for 24 h, only to detect such late payments.
   The customer is told TIMED_OUT in two cases: when the payment window closes, or when a one-session channel's wait ends while the payment is still open. In both cases the payment is closed first: one last provider query, and then PAYMENT_TIMEOUT, never PAYMENT_FAILED. So what the customer was told and what PayPort does never differ.
   Windows are configurable by provider and by channel. Their values are owner-approved and pinned by `PaymentWindowsTest`.
4. **Outcome.** Every transaction response carries `outcome` and `outcomeMessage`. `GET /transactions/{ref}/outcome?waitSeconds=N` and `POST /pay?waitSeconds=N` wait (up to 180 s) for a final outcome.
5. **Idempotency keys.** A POST with `Idempotency-Key` is performed once; a repeat returns the stored response; the same key with a different body gets 422.
6. **SMS only on success.** One SMS, when the transaction completes. Failures and timeouts are shown in the channel only.
7. **Provider wording.** The customer sees the provider's own message.

## Consequences
- The customer is told the outcome within the provider's window. That window is the trade-off between a fast answer and a late approval that must be honoured or refunded.
- Idempotency stops PayPort acting twice on a retried request. It does not stop a provider acting twice: attempt references, reconciliation and the late-payment queue cover that.
