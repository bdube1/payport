# Phase 2 report: payment lifecycle

_Branch `phase2-payment-lifecycle`, built on `phase1-spring-boot-upgrade`. Design: ADR-0010. Operations: `docs/runbooks/PAYMENT_OPERATIONS.md`._

## Acceptance criterion
Under duplicate, out-of-order and before-response callbacks, timeout→late success, timeout→definite failure, provider down and concurrent identical requests, there is:
- exactly one financial effect;
- the correct final state;
- no blind retry.

## What changed

| Area | Before | After |
|---|---|---|
| **Submitting a payment** | Provider called inside the DB transaction; an exception rolled back to INITIATED, so a second push was possible | PAYMENT_PENDING, the attempt and the resolver check are committed first. The call runs outside any transaction, and the outcome is recorded afterwards without overwriting a callback's result |
| **Timeouts and 5xx** | Marked FAILED, with a failure SMS | **UNKNOWN**: stays pending and is resolved by status query. Only connect failures and 4xx are definite failures |
| **Concurrency** | CAS on status, while some paths saved stale entities | Row lock per payment on every lifecycle path; dynamic updates; the audit row is in the same transaction |
| **Callbacks** | Processed straight from the request; a success after a failure was silently dropped | Inbox (`payment_callbacks`): stored first, deduplicated, outcome recorded. Contradictions are **CONFLICT** for review |
| **ZimSwitch webhooks** | Structure check only, so they could be forged (R45) | Confirmed by status query, like EcoCash and OneMoney |
| **Resolver** | In-memory clock; single instance only | `payment_checks` under a DB lease; survives restarts; 72 h → MANUAL; never fails on time |
| **Watchdog** | Stale save could overwrite a confirmation | Under the row lock, only from PENDING |
| **Fulfilment** | In-memory queue; lost on restart; could run before commit; timeout treated as failure; automatic retries never actually ran | `fulfilment_jobs` written in the confirming transaction and leased. An unknown delivery is resolved by `queryDelivery`, never re-sent, and becomes MANUAL after 2 h |
| **Staff test endpoints** | "Skip payment" and direct ZESA purchase available to any staff user, without audit (R46) | Off unless explicitly enabled |
| **Removed** | Compile-time `TEST_BYPASS` switch that simulated provider success | — |

## Evidence
CI on GitHub Actions (bdube1/payport). Both scenario suites boot the whole application against MySQL 8 with real Flyway migrations. The providers are scriptable simulators, test-scope only. Both suites are in the required-test guard, so CI fails if they skip.

**`PaymentLifecycleScenarioTest`** (23 scenarios):
- Happy path.
- 15 duplicate deliveries of one callback, 10 of them concurrent: exactly one applied, one inbox row, one confirmation, one fulfilment enqueue and one SMS.
- Out-of-order callbacks, success after failure and failure after success, are CONFLICT. A confirmation is never undone.
- An amount mismatch and a bad signature change nothing.
- A callback arriving before the submit response confirms once. The late response sends no "pending" SMS, including when that response is a timeout.
- Read timeout → late success, by resolver query or by callback.
- 5xx → definite failure by query.
- Connection refused and 4xx are immediate failures.
- Provider down for queries: the payment stays open, goes to MANUAL after 72 h, and is not failed.
- Resolver amount mismatch → MANUAL.
- 10 concurrent `/pay` requests → **one push**; 9 are refused.
- A retry after an unknown outcome is refused.
- Watchdog timeout → late confirmation.
- 15 payments with racing watchdog runs and confirmations: every one is confirmed exactly once.
- Query-verified provider: a forged success changes nothing until the provider confirms, and the provider's failure overrules a callback's claimed success.
- Payments pending without a check are picked up.
- A callback for an unknown payment is recorded and ignored.

**`FulfilmentJobScenarioTest`** (10 scenarios):
- The job is created in the confirming transaction, and the delivery is sent once.
- 6 concurrent workers → one delivery.
- Delivery timeout:
  - never re-sent, even when re-queued;
  - resolved as delivered by query;
  - or confirmed not delivered, after which the ops retry delivers once;
  - or unknown for too long → MANUAL, with the ops retry refused.
- A definite rejection fails with no automatic retry.
- Connection refused is a definite non-delivery.
- Instance dies mid-delivery → resolved by query, not re-sent.
- Instance dies before sending → queued again and delivered once.
- A confirmed payment without a job is picked up.

**`SubmissionErrorsTest`:** the error classification.

CI results: see `docs/PROJECT_STATUS.md`.

## Not verified
- **Behaviour against real provider sandboxes.** The adaptors' error classification relies on how each HTTP or SOAP client reports timeouts. Each provider should be exercised in its sandbox, or in staging, with a forced timeout.
- **Real provider status queries:**
  - ZimSwitch query-confirmed callbacks depend on `queryStatus(checkoutId)` working in production.
  - The eSolutions "resend" lookups and the NetOne and Telecel delivery queries are existing code, now also used for resolution.
- **A multi-instance deployment** has been simulated with threads, but not with separate processes.

## Decisions for you
1. **R46 test endpoints.** Do ops use `/api/v1/test/skip-payment` or the direct ZESA purchase? If yes, they should become a four-eyes ops action. Until then they stay off.
2. **Automatic retry of definite delivery failures.** It is off by default. Before Phase 2 it never ran, so this matches what production actually did. To turn it on, we need each provider's failure codes confirmed as safe to retry.
3. **Econet delivery status (R50).** Econet has no delivery query, so every Econet delivery timeout goes to a person. It is worth asking Econet for a status API.
4. **Customer-visible changes:**
   - A provider timeout now shows "pending", not "failed".
   - A definite delivery failure now sends the "delivery delayed" SMS. Before Phase 2 this SMS was never sent, because the retry counter never reached its limit.

## Deployment notes
- **Migrations:** V37 and V38 create tables only, and are safe online.
- **On first start:**
  - The resolver opens checks for payments already pending.
  - The worker queues deliveries for payments confirmed in the last 24 h that have no job.
  - Older stuck confirmed payments are left for review (runbook §3 query, with `status = 'PAYMENT_CONFIRMED'`).
- **Callbacks keep their URLs and their responses** (OneMoney "SUCCESS"; ZimSwitch `{"status":"received"}`).
