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
3. **Late payments.** Checks continue for 24 h, only to detect a late payment. A success after the customer was told "timed out" is held for review (`PAID_AFTER_TIMEOUT`). Ops then either honour it (four-eyes approval, then the payment is delivered) or refund it. `PAYMENT_LATE_SUCCESS=HONOUR` delivers automatically instead.
4. **Outcome.** Every transaction response carries `outcome` and `outcomeMessage`. `GET /transactions/{ref}/outcome?waitSeconds=N` and `POST /pay?waitSeconds=N` wait (up to 180 s) for a final outcome.
5. **Idempotency keys.** A POST with `Idempotency-Key` is performed once; a repeat returns the stored response; the same key with a different body gets 422.
6. **SMS only on success.** One SMS, when the transaction completes. Failures and timeouts are shown in the channel only.
7. **Provider wording.** The customer sees the provider's own message.

## Consequences
- The customer is told the outcome within the provider's window. That window is the trade-off between a fast answer and a late approval that must be honoured or refunded.
- Idempotency stops PayPort acting twice on a retried request. It does not stop a provider acting twice: attempt references, reconciliation and the late-payment queue cover that.
