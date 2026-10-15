# Runbook: payment and delivery exceptions (Phase 2)

These are the cases the system deliberately does **not** decide on its own. Until the ops console arrives (Phase 6), find them with the queries below. All times are application time (UTC in containers).

## 1. Payments with no definite provider answer (`payment_checks.status = 'MANUAL'`)

```sql
SELECT c.transaction_reference, c.provider_code, c.submitted_at, c.check_count, c.last_result, t.status, t.amount_cents, t.currency
FROM payment_checks c JOIN transactions t ON t.reference = c.transaction_reference
WHERE c.status = 'MANUAL' ORDER BY c.closed_at DESC;
```

**Meaning:** either 72 hours passed with no definite answer, or the provider confirmed a different amount (`last_result` starts with `AMOUNT MISMATCH`). The payment was **not** marked failed.

**Action:** check the payment in the provider's portal.
- **Paid at the right amount:** confirm through the provider's status query path. If the payment is still pending, reopen the check with the update below; the resolver asks again on its next run.
- **Not paid:** record the failure with evidence.
- **Wrong amount:** escalate to finance.

```sql
UPDATE payment_checks SET status = 'OPEN', next_check_at = UTC_TIMESTAMP(6), closed_at = NULL WHERE transaction_reference = ?;
```

## 2. Callbacks that contradict what we recorded (`payment_callbacks.status = 'CONFLICT'`)

```sql
SELECT id, provider_code, payport_reference, claimed_status, outcome_detail, received_count, first_received_at
FROM payment_callbacks WHERE status = 'CONFLICT' ORDER BY first_received_at DESC;
```

| Case | Meaning | Action |
|---|---|---|
| `SUCCESS` for a payment recorded as FAILED or CANCELLED | The customer may have paid for something we did not deliver | Check with the provider. If paid: deliver manually, or refund |
| `FAILED` for a payment recorded as paid | The provider contradicts itself | Check with the provider before anything else; never reverse automatically |
| Amount mismatch | The provider reports a different amount | Finance review |

## 3. Deliveries with an unknown outcome (`fulfilment_jobs.status IN ('UNKNOWN','MANUAL')`)

```sql
SELECT j.transaction_reference, j.status, j.attempts, j.unknown_since, j.last_result, t.status, t.product_type_id, t.recipient_identifier
FROM fulfilment_jobs j JOIN transactions t ON t.reference = j.transaction_reference
WHERE j.status IN ('UNKNOWN','MANUAL') ORDER BY j.unknown_since;
```

- **UNKNOWN:** the worker asks the provider every 5 minutes.
- **MANUAL:** there has been no definite answer for 2 hours (`FULFILMENT_UNKNOWN_MANUAL_AFTER_MINUTES`). Econet airtime always ends here after a timeout, because Econet offers no delivery query.

**Never re-deliver before confirming with the provider that the first delivery did not happen.** The ops "retry" action is refused for these payments on purpose.

## 4. Definite delivery failures (`fulfilment_jobs.status = 'FAILED'`)

The provider said no. The payment is FULFILMENT_FAILED, and the customer received the "delivery delayed" SMS.

**Action:** use the existing ops retry or refund. A retry queues the job again. Automatic retry is off (`FULFILMENT_AUTO_RETRY=false`) until each provider's failure codes are confirmed as safe to retry.

## 5. Health signals to alert on

| Signal | Source |
|---|---|
| `[RESOLVER] ... need manual resolution` | Error log |
| `[FULFILMENT_WORKER] ... interrupted mid-delivery` | Error log; an instance died during delivery |
| `[ORCHESTRATOR] CONTRADICTION` / `CONFLICT` | Error log |
| `payment-resolver` job runs | `platform_job_runs`: a FAILED status, or no run for 5 minutes |
| Count of OPEN checks older than 1 hour | `payment_checks` |

## 6. Settings

| Variable | Default | Effect |
|---|---|---|
| `PAYMENT_QUERY_PROVIDERS` | `ECOCASH,ONEMONEY,ZIMSWITCH` | Callbacks from these providers are confirmed by status query |
| `PAYMENT_RESOLVER_ENABLED` | `true` | Uncertain-outcome resolver |
| `PAYMENT_WATCHDOG_ENABLED` | `true` | PENDING → TIMEOUT after `payport.gateway.payment-timeout-seconds` |
| `FULFILMENT_WORKER_ENABLED` | `true` | Delivery worker |
| `FULFILMENT_UNKNOWN_MANUAL_AFTER_MINUTES` | `120` | Unknown delivery → MANUAL |
| `FULFILMENT_AUTO_RETRY` | `false` | Automatic retry of definite delivery failures |
| `PAYPORT_TEST_ENDPOINTS_ENABLED` | `false` | Staff test endpoints (R46). Never enable in production |
