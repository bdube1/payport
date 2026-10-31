# Frontend integration plan (Phases 2–7)

## Status (2026-10-16)

Done in the user's order. Each item is a pull request; every app now has a build or typecheck workflow.

| Order | App | Pull request | Covers |
|---|---|---|---|
| 1 | consumer | `payport-consumer` #1 | Section 1 items 1, 3, 4, 5; biller catalogue and lookup; PAYMENT_NOT_ALLOWED; no logout on 403 |
| 1 | mobile | `payport-mobile` #1 | Section 1 items 2–5; **push token register and revoke**; biller lookup |
| 2 | ops | `payport-ops` #1 | Work queues; KYB four-eyes; destinations; enablements; settlement batches; recon exceptions; bank ISO 20022 messages; integrations; markets |
| 3 | merchant | `payport-merchant` #2 (on top of #1, signup) | API keys; go-live; destinations; balances and statement; payouts; webhooks console |
| 4 | ops | `payport-ops` #1 | Compliance console (COMPLIANCE only); compliance authorities (ADMIN); dispute deadlines; COMPLIANCE users |

**Still to do**
- End-to-end checks against a staging backend.
- Push delivery on a real device. This needs `expo.extra.eas.projectId` from `eas init`.
- The ledger screens (section 2, ops) remain on the API only.


_Reviewed 2026-10-16 against `payport-ops` (48cb36a), `payport-merchant` (1df842d), `payport-consumer` (4257a6b) and `payport-mobile` (main). The backend is on `phase2-payment-lifecycle`._

**Approach (owner's decision).** Finish the backend first, then wire the frontends.

**How the review was done.**
- Every API call in the four repos was checked against the backend's routes. **All of them still exist** (one exception: mobile's push-token call). So nothing 404s.
- The breakages are about behaviour and data shapes. The four groups below are ordered so that the screens which are wrong today come first.

## 0. Already fixed in the backend (no frontend change needed)

**Error messages never reached people.**
- The apps read `response.data.message` in about 80 places: ops 42, merchant 16, consumer 15, mobile 8.
- The backend only sent `error.message`.
- So staff and customers saw generic fallbacks instead of the real reason, including the provider's own wording the owner asked us to show.
- Error responses now carry `message` at the top level as well. Every one of those screens shows the real text without a frontend release.

## 1. Must fix (wrong behaviour today)

| # | App | Where | Problem | Change |
|---|---|---|---|---|
| 1 | consumer | `pages/Processing.jsx` | Treats `PAYMENT_TIMEOUT` as "still checking" and keeps polling for a late success. By owner decision, a timed-out payment is final: a late success is refunded, never delivered. | Drive the screen from the response's **`outcome`** field: `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `NO_RESPONSE`, `PROCESSING`, `REFUNDED`. Show `outcomeMessage` exactly as sent (the provider's words). Stop polling on any final outcome. |
| 2 | mobile | `app/processing/[ref].tsx`, `app/status/[ref].tsx`, `(tabs)/history.tsx` | `FINAL` lists `'TIMEOUT'`, which is not a status. The real one is `PAYMENT_TIMEOUT`, so a timed-out payment spins forever. | Same as #1: use `outcome` and `outcomeMessage`. |
| 3 | consumer, mobile | `lib/api.*` initiate/pay/status | `X-Transaction-Token` is never sent. Guests get the redacted public view (no failure reason). Once `payport.security.transaction-token.enforce=true` they get 404. | Keep the `accessToken` returned by `/initiate` for that reference and send it as `X-Transaction-Token` on `/pay`, `GET /transactions/{ref}` and `/outcome`. |
| 4 | consumer, mobile | `/pay` calls | No `Idempotency-Key`. A double tap or a network retry can submit twice. The backend refuses the second push, but the customer sees an error. | Send `Idempotency-Key: <uuid per pay attempt>` on `/initiate` and `/pay`. Reuse it when retrying the same attempt. |
| 5 | consumer, mobile | pay error handling | A suspended or failing provider now returns **503 `PROVIDER_UNAVAILABLE`** ("No money was taken"). | Show the message and offer the other payment methods. Do not show a generic failure. |
| 6 | ops | `pages/MerchantsPage.jsx` → `approveMerchant` | Approval is now four-eyes. The single-click **Approve returns 409 `KYB_RECOMMENDATION_REQUIRED`**. | Replace with the KYB case flow (section 2, ops). |
| 7 | consumer | `Checkout.jsx`, `PaymentLink.jsx` (hosted checkout) | Live payments in a currency Payport has not enabled for the merchant are refused (400, message "Live payments in ZWG are not enabled for this account"). | No code change once #0 shipped. Optionally hide the pay button and show the message on load. |

## 2. New screens and flows

### payport-merchant (merchant dashboard)

| Screen | Endpoints (`/api/v1/merchant…`) | Notes |
|---|---|---|
| **API keys** (extend `ApiKeys.jsx`) | `POST /api-keys` `{environment, label, scopes[], expiresAt}`; `POST /api-keys/{id}/rotate?overlapHours=`; `POST /api-keys/{id}/revoke`; `GET /api-keys` (now includes `scopes`, `expiresAt`, `rotatedFromId`) | Show the secret **once**, with a copy button. Scope picker with 9 scopes, default all. LIVE is disabled until `/readiness` says `liveReady` (409 `NOT_LIVE_READY` lists what's missing). Only the owner can create live keys. |
| **Go-live checklist** (new) | `GET /readiness` | Merchant approved; currency enabled; verified settlement destination. Each item links to its screen. |
| **Settlement destinations** (new, owner only) | `GET`/`POST /settlement-destinations` | States: PENDING_VERIFICATION, VERIFIED, REJECTED, RETIRED. The account number is masked. Explain that Payport verifies before any payout. |
| **Balances** (replace the `/balance` card on Dashboard) | `GET /balances?mode=live\|test` | Four buckets per currency: pending (hold), available, in settlement, paid out. Test/live toggle. |
| **Statement** (new) | `GET /statement?currency=&from=&to=&mode=` | Lines with a running net. CSV export can be done client-side. |
| **Payouts** (replace `Settlements.jsx`) | `GET /payouts` | Combines new-settlement payouts and legacy settlements. Status, bank reference, paid date, return reason. |
| **Webhooks** (extend `Webhooks.jsx`) | `POST /webhooks/{id}/test`; `POST /webhooks/deliveries/{id}/replay`; `POST /webhooks/{id}/rotate-secret`; `POST /webhooks/{id}/disable`; deliveries now include `eventId` and `replayOf` | https only, public hosts only: show the 400 message. Show the v2 verification snippet from `docs/api/MERCHANT_API.md`. |
| **Developers** | static | Link to the API reference and the sandbox magic numbers (`…0000001` and `…0000002` decline). |

### payport-ops (staff console)

| Screen | Endpoints | Notes |
|---|---|---|
| **Work queues** (replace or extend `AttentionQueuePage`) | `GET /admin/platform/work-queues` | 9 queues with counts. Each row links to its screen. Use it as the default landing page for OPS. |
| **KYB case** (in the merchant drawer; replaces Approve) | `GET /admin/platform/kyb/{id}`; `POST …/assign`; `POST …/recommend` `{recommendation, checklist{5}, note}`; `POST …/decide` `{accept, note}` | Show the recommender. The decide button is disabled for the recommender (the server returns 409 as well). The first test key is shown once to the decider. |
| **Settlement destinations to verify** | `GET /admin/platform/settlement-destinations?status=`; `POST …/{id}/verify` `{approve, note}` | The requester cannot verify. |
| **Enablements** (merchant drawer) | `POST /admin/platform/merchants/{id}/enablements` `{country, currency, enabled}`; `GET …/readiness` | Disabling stops open links and sessions too. |
| **Settlement batches** (new; keep the legacy page during parallel run) | `/admin/finance/settlement/eligible`, `batches`, `batches/{id}`, `…/approve`, `…/cancel`, `…/instruct`, `payout-results` | Draft → approve (maker ≠ checker) → compare with legacy. Instruct is disabled while in PARALLEL mode. |
| **Reconciliation exceptions** | `GET /admin/finance/recon/exceptions`; `POST …/{id}/resolve`; statements upload and run | Late payments: the only actions are refund, refunded and provider corrected. Never deliver. |
| **Integrations** (replace `provider-health`) | `GET /admin/integrations`, `/{code}`; `POST /{code}/suspend` `{reason}`, `/resume`, `/verifications` | Per provider: capabilities, configured environment (TEST, PRODUCTION, INSECURE_HTTP), verification level and evidence, circuit state, latency, kill switch. |
| **Ledger** (Phase 1, not yet in the UI) | `/admin/ledger/*` | Shadow comparison and integrity checks; tax rates (four-eyes). |

### payport-consumer and payport-mobile

| Change | Endpoints |
|---|---|
| Bill payment uses the biller catalogue and lookup | `GET /api/v1/billers` (label, format, hint, currencies); `POST /api/v1/billers/{code}/lookup` → `holderName`, `acceptedCurrencies`, `payable` and `notPayableReason`. Validate the format client-side from `accountPattern`. The server enforces it again at `/initiate`. |
| USSD-style instant answer (optional for app/web) | `POST /pay?waitSeconds=N` or `GET /transactions/{ref}/outcome?waitSeconds=N` |
| Show provider messages verbatim | `outcomeMessage` (success: delivery; failure: the provider's words) |

## 3. Backend gaps and decisions found during the review

| Item | Decision needed |
|---|---|
| Push notifications | **Decided (yes), backend done.** `POST`/`DELETE /api/v1/profile/push-token` and pushes for every final outcome. **Mobile still has to obtain and register the token**: `expo-notifications` `getExpoPushTokenAsync({projectId})` after login, `registerPushToken(token)`, and DELETE on logout. Today `registerPushToken` exists in `lib/api.ts` but is never called. |
| Merchant signup | **Decided (merchant portal), done:** `payport-merchant` PR #1 (`/signup`). |
| Public bill lookups | **Decided:** stay public, with no login and no captcha. They are rate-limited per IP. |

### Phase 6 additions (payport-ops)

| Screen | Endpoints | Notes |
|---|---|---|
| **Compliance console** (new section, COMPLIANCE role only; add `COMPLIANCE` to `ROUTE_ROLES`) | `/api/v1/admin/compliance/alerts`, `cases`, `cases/{id}` (assign, notes, propose, decide), `rules` and `rule-changes`, `screening/lists` (create, load, check), `reports` (STR/CTR: approve, submitted) | The decide and approve buttons are disabled for the proposer or preparer (the server refuses as well). Never show compliance data to other roles. |
| **Dispute timelines** (in `DisputesPage`) | `/api/v1/admin/dispute-timelines`, `…/{id}/acknowledge` | Badges: ON_TRACK, AT_RISK, BREACHED, MET, MET_LATE. |
| **Users page** | existing | Allow creating `COMPLIANCE` users. |
| Consumer and mobile | `/initiate` may return **403 `PAYMENT_NOT_ALLOWED`** | Show the message as sent: it is neutral and quotes the reference. |

## 4. Order of work (after the backend is complete)

1. **Consumer and mobile:** items 1–5 in section 1. These are customer-visible correctness fixes.
2. **Ops:** KYB, destinations, enablements, work queues. Merchant approval is blocked until this ships, but the API works meanwhile.
3. **Merchant:** keys, go-live, destinations, balances, statement, payouts, webhooks console.
4. **Ops:** settlement batches, recon exceptions, integrations, ledger.

Each step gets an end-to-end check against a staging backend. Builds go through their own CI, which these repos don't have yet; a minimal one would be lint + build + a smoke test.

## 5. Deployment settings the frontends depend on

- `PAYPORT_ENV=production` (the default). The backend refuses to start without `PAYPORT_DATA_KEY`.
- `PAYPORT_DATA_KEY`: `openssl rand -base64 32`. Keep it in the secrets manager and never rotate it casually: it decrypts webhook secrets.
- CORS already lists `merchant.payport.africa`. Confirm the ops and consumer origins in `SecurityConfig` when the new screens go to staging.
