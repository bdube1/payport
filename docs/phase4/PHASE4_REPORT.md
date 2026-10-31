# Phase 4: merchant and developer platform

_Branch `phase2-payment-lifecycle`, 2026-10-16. The scope comes from roadmap doc 08, Phase 4._

**Acceptance criterion:** "A new test merchant onboards, receives keys, integrates via the docs, takes simulator payments, sees balances and settlement, and receives webhooks, all without staff database access."

**Evidence:** CI run 38875403779, all jobs green (build-and-test: 142 passed, 0 failed; ledger-db: 106/106; migrations through V42 pass). The acceptance test is `MerchantPlatformAcceptanceTest`. It boots the whole application on MySQL 8 and runs the journey over HTTP only, with a real local webhook receiver. Staff act only through the admin API.

## What was built

| Area | What it does | Where |
|---|---|---|
| **KYB case management** | A merchant is approved only after one staff member *recommends* and a **different** staff member *decides*. To recommend APPROVE, every checklist item must be confirmed: registration documents, directors, sanctions, business activity, bank account. Rejecting needs a reason. The four-eyes rule is enforced twice: in code, and by a database CHECK (`decided_by <> recommended_by`). The old `/admin/merchants/{id}/approve` is now only the decision step; called without someone else's recommendation it answers 409. | `merchant/MerchantPlatformService`, `AdminPlatformController`, V42 |
| **Settlement destinations** | The merchant owner adds an account per currency. Payport staff verify it, and the verifier cannot be the requester (DB CHECK). Verifying a new account retires the old one. Settlement pays to the verified account. **Automatic settlement approval refuses any merchant without a verified destination.** Until a merchant has one, the account captured at signup is still used by the manual flow. | same, `SettlementBatchService.destinationFor`, `SettlementAutomation.policyRefusal` |
| **Market enablements** | Staff enable a currency (and country) per merchant. A **live** API key can only create sessions in enabled currencies. Merchants that were already ACTIVE are backfilled with USD and ZWG, so nothing changes for them. | V42, `CheckoutSessionService.createSession` |
| **Scoped API keys** | Nine scopes. Keys can have an expiry. Rotation keeps the old key working for an overlap of at most 168 h. Merchants can revoke their own keys. Key management, team, settlement destinations and documents are **dashboard-only**: a leaked server key cannot mint keys or redirect payouts. LIVE keys require the merchant to be live-ready (ACTIVE, an enabled currency, a verified destination) and can only be created by the owner. | `ApiKeyScopes`, `ApiKeyAuthenticationFilter`, `ApiKeyService`, `ApiKeyController` |
| **Webhooks v2** | **SSRF guard (R13):** https only; public addresses only, with DNS re-checked on every delivery; no credentials in URLs; restricted ports; redirects never followed. Link-local and metadata addresses are refused even in local-testing mode. **Secrets encrypted at rest (R14):** AES-256-GCM with `PAYPORT_DATA_KEY`; legacy plaintext secrets are re-sealed on use. **Signing:** `Payport-Signature: t=..,v1=HMAC(t.body)` gives replay protection, and `Payport-Event-Id` lets merchants dedupe. The v1 header is kept for existing integrations. **Delivery:** sent after the database commit, so no row lock is held while a merchant responds. **Console:** delivery history, replay (same event id), test ping, secret rotation, disable. **Payout events:** `payout.paid`, `payout.returned`, `payout.failed`. | `WebhookTargetGuard`, `SecretBox`, `WebhookService`, `WebhookController` |
| **Merchant money APIs** | `/balances` splits into pending (inside the hold), available, in settlement and paid out, net of fees and refunds. `/statement` lists payments, refunds and payouts with a running net. `/payouts` covers new-settlement payouts and legacy settlements. Drafts made in PARALLEL comparison mode are not real payouts and are never shown to merchants. | `MerchantBalanceService`, `MerchantPlatformController` |
| **Sandbox** | Test-mode sessions never reach a provider. Magic phone endings `0000001` and `0000002` produce declines with provider-style messages; any other number succeeds. | `SandboxScenarios`, `simulateTestPayment` |
| **Admin work queues** | One call lists everything waiting for a person: KYB cases awaiting recommendation or decision, destinations to verify, settlement batches to approve, late payments needing a refund, recon exceptions, disputes, failed webhooks, and manual fulfilment. | `GET /api/v1/admin/platform/work-queues` |
| **API reference** | Covers keys and scopes, checkout, money, webhooks (with verification code), the sandbox and the error codes. | `docs/api/MERCHANT_API.md` |

## Acceptance journey (what the test proves)

1. Signup over the public endpoint.
2. One staff member cannot approve alone (409). An incomplete checklist is refused (400). The recommender cannot decide (409). A second staff member approves, and the merchant becomes ACTIVE.
3. The merchant logs in and creates a scoped TEST key.
4. Asking for a LIVE key is refused with `NOT_LIVE_READY`.
5. The scoped key is refused on webhooks (no scope) and on key management (dashboard-only).
6. A webhook is registered. A metadata address is refused.
7. A sandbox payment is PAID and `checkout_session.paid` arrives. Its v2 signature verifies exactly as the docs describe, and the event id header matches the body.
8. The magic-number decline gives FAILED and a signed `checkout_session.failed`.
9. Balances (test mode) show the gross, the fee, and the net as pending. The statement shows the payment. Payouts are listed.
10. Replaying the delivery redelivers it with the **same** event id.
11. Rotating the key with no overlap gives `API_KEY_EXPIRED` on the old key; the new key works with the same scopes.
12. The merchant adds a settlement destination and staff verify it. The destination shows in the work queue first, and the account number is not echoed back.
13. Staff enable USD. Readiness becomes true and a LIVE key is issued.
14. The live key is refused for ZWG (not enabled) and creates a USD session with `livemode: true`.

## Owner decisions kept

- **Settlement** stays in PARALLEL (draft, approve, compare) mode. Automatic settlement is built and switched off (`SETTLEMENT_AUTOMATION_ENABLED=false`, auto-approve limit 0). It now also requires verified destinations.
- **Payment windows** are unchanged; `PaymentWindowsTest` pins them.
- **Late payments** are refund-only.

## Not done / follow-ups

- **Live sessions created without an API key** (payment links, dashboard checkouts) are not yet checked against enablements. The backfill keeps existing merchants working; new merchants get enablements from staff. The next step is to enforce it there too.
- **Egress proxy for webhooks** (the last part of R13) is infrastructure work. The application-level guard is in place.
- **Database TLS and the secrets manager** (rest of R14) are infrastructure work.
- **KYB document checks** are a staff checklist, not automated (no registry or sanctions API).
- **Frontend.** The dashboard and admin console screens for these APIs are in the frontend repos, which are not available here.
