# Runbook: payment and delivery exceptions (Phase 2)

These are the cases the system deliberately does **not** decide on its own. Until the ops console arrives (Phase 6), find them with the queries below. All times are application time (UTC in containers).

## 1. Payments with no definite provider answer (`payment_checks.status = 'MANUAL'`)

```sql
SELECT c.transaction_reference, c.provider_code, c.submitted_at, c.check_count, c.last_result, t.status, t.amount_cents, t.currency
FROM payment_checks c JOIN transactions t ON t.reference = c.transaction_reference
WHERE c.status = 'MANUAL' ORDER BY c.closed_at DESC;
```

**Meaning:** either 24 hours (`PAYMENT_LATE_DETECTION_HOURS`) passed with no definite answer after the payment window closed, or the provider confirmed a different amount (`last_result` starts with `AMOUNT MISMATCH`). The payment was **not** marked failed.

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

The provider said no, with a code its specification marks as final (`docs/phase2/PROVIDER_ERROR_CODES.md`). The payment is FULFILMENT_FAILED; the customer sees the provider's own message (no SMS is sent for failures). Temporary codes are retried automatically and do not land here unless every retry failed.

**Action:** use the existing ops retry or refund. A retry queues the job again.

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
| `FULFILMENT_AUTO_RETRY` | `true` | Automatic retry of delivery failures whose code the provider spec marks as temporary |
| `PAYMENT_TIMEOUT_SECONDS` | `180` | Payment window; `ZIMSWITCH_TIMEOUT_SECONDS` 1800, `OMARI_TIMEOUT_SECONDS` 300. Per-channel overrides `payport.payments.window-seconds.<CHANNEL>[.<PROVIDER>]` (none set). **Owner approval required to change any window** |
| `PAYMENT_LATE_DETECTION_HOURS` | `24` | How long after the window closes PayPort keeps asking, to detect a late payment |
| `PAYMENT_MAX_ATTEMPTS` | `3` | Attempts per transaction after definite failures |
| `ZIMSWITCH_WEBHOOK_SECRET` | none | AES-256-GCM key of the OPPWA webhook; without it encrypted webhooks are rejected (the resolver still confirms payments) |
| `SETTLEMENT_MODE` | `PARALLEL` | PARALLEL: draft and approve only. LIVE: legacy settlement stops, batches can be instructed |
| `SETTLEMENT_HOLD_HOURS` | `24` | Collections younger than this are not eligible |
| `SETTLEMENT_REQUIRE_RECONCILED` | `true` | Only collections MATCHED on a provider statement are eligible |
| `SETTLEMENT_DUAL_APPROVAL_THRESHOLD_MINOR` | `1000000` | Batches above this net (minor units) need two approvers |

## 7. Late payments (`recon_exceptions.category = 'PAID_AFTER_TIMEOUT'`)

The provider confirmed a payment after the customer was told it timed out (or got no response): when the payment window closed, or when a USSD session's wait ended. **It is never fulfilled**: no person or process can confirm it (the state machine has no path from PAYMENT_TIMEOUT to PAYMENT_CONFIRMED). The money must go back:

1. Refund through the provider (EcoCash refund, OneMoney refund, O'mari void, ZimSwitch back office).
2. Record it: `POST /api/v1/admin/finance/recon/exceptions/{id}/resolve` with `{"resolution":"REFUNDED","note":"<provider refund reference>"}`. Only REFUND_REQUIRED, REFUNDED or PROVIDER_CORRECTED are accepted for this category.

## 8. Statement reconciliation

`POST /api/v1/admin/finance/recon/statements?provider=ECOCASH` with the file (`format=CSV` for PayPort's generic CSV: `reference,provider_reference,amount,currency,timestamp[,type]`). The same file is imported only once. Review `GET /api/v1/admin/finance/recon/exceptions`; record each decision with a note (`EXPLAINED_TIMING`, `REFUND_REQUIRED`, `REFUNDED`, `PROVIDER_CORRECTED`, `WRITTEN_OFF`, `FALSE_POSITIVE`). Reconciliation never changes a payment.

## 9. Settlement batches

1. `GET /settlement/eligible?currency=USD`, then `POST /settlement/batches {"currency":"USD"}` (maker).
2. `GET /settlement/batches/{id}`: items, and the comparison with the legacy settlement during the parallel run.
3. `POST /settlement/batches/{id}/approve` by one or two other FINANCE users.
4. LIVE mode only: `POST /settlement/batches/{id}/instruct` returns the payout file. Calling it again returns the same file; it never creates new instructions.
5. `POST /settlement/payout-results` with the bank's file (`instruction_ref,status,bank_reference,reason`). PAID needs the bank reference; the file's hash is the proof. Returned payouts release their collections for the next batch.
