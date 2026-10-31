# Payport Merchant API

Base URL: `https://api.payport.africa` (sandbox and live share the URL. The **key** decides which one you use.)
All bodies are JSON. Every response looks like this:

```json
{ "success": true, "data": { ... }, "error": null, "meta": { "requestId": "..." } }
```

When something fails, `success` is `false` and `error` holds a `code` and a `message`. Send `X-Request-ID` if you want your own id echoed back.
Amounts are always integers in **minor units** (cents). Currencies are `USD` and `ZWG`.

## 1. Getting started

1. **Sign up.** Call `POST /api/v1/merchant-portal/signup` (or use the dashboard). Payport reviews the application (KYB). Two different Payport staff members must agree before an account is approved.
2. **Get keys.** Once approved, log in to the dashboard and create keys under *Developers → API keys*. A `TEST` key works immediately.
3. **Build against the sandbox** with the test key (section 6).
4. **Go live.** Live keys can be created once you have:
   - added a settlement destination (dashboard → *Settlement*), which Payport staff then verify;
   - had Payport enable the currencies you will sell in.

   `GET /api/v1/merchant/readiness` lists anything still missing.

## 2. Authentication and keys

Send `Authorization: Bearer sk_test_...` or `Authorization: Bearer sk_live_...`.

| Response | Meaning |
|---|---|
| 401 `API_KEY_EXPIRED` | The key has expired, usually after a rotation. Use its replacement. |
| 403 `INSUFFICIENT_SCOPE` | The key's scopes don't cover this endpoint. |

**Scopes**, chosen when you create a key. The default is all of them:

| Scope | Allows |
|---|---|
| `checkout:read` | `GET /checkout-sessions...` |
| `checkout:write` | `POST /checkout-sessions` |
| `refunds:write` | `POST /checkout-sessions/{id}/refunds` |
| `disputes:read` / `disputes:write` | disputes |
| `payment_links:write` | payment links |
| `balances:read` | `/balances`, `/statement`, `/balance` |
| `payouts:read` | `/payouts`, `/settlements` |
| `webhooks:manage` | `/webhooks...` |

Some things **can't be done with an API key at all**. You need a dashboard session for: managing API keys, managing your team, changing settlement destinations, and handling documents or your application. That way a leaked server key can't create more keys or redirect your payouts.

**Key management (dashboard session):**

| Call | What it does |
|---|---|
| `POST /api/v1/merchant/api-keys` `{ "environment": "TEST"\|"LIVE", "label": "...", "scopes": ["checkout:write", ...], "expiresAt": "2027-01-01T00:00:00" }` | Creates a key. The secret is returned **once**. Only the owner can create live keys. |
| `POST /api/v1/merchant/api-keys/{id}/rotate?overlapHours=24` | Issues a new key with the same environment and scopes. The old key keeps working for the overlap (at most 168 hours), so you can deploy without downtime. |
| `POST /api/v1/merchant/api-keys/{id}/revoke` | Stops a key immediately. |
| `GET /api/v1/merchant/api-keys` | Lists keys (never the secrets). |

## 3. Taking a payment

```
POST /api/v1/merchant/checkout-sessions
{ "amountCents": 2500, "currency": "USD", "reference": "order-1001", "description": "Order 1001",
  "customerPhone": "+263771234567", "successUrl": "https://shop.example/ok", "cancelUrl": "https://shop.example/cancel",
  "metadata": "{\"orderId\":1001}" }
```

The response contains `id`, `status` (`CREATED`), `checkoutUrl` and `livemode`.

Redirect the customer to `checkoutUrl`. The hosted page collects the payment method (EcoCash, OneMoney, O'mari, card via ZimSwitch). Session status moves through `CREATED` → `PENDING` → `PAID` | `FAILED` | `EXPIRED`. On a refund it moves to `PARTIALLY_REFUNDED` or `REFUNDED`.

To check a session, call `GET /api/v1/merchant/checkout-sessions/{id}` or `GET /api/v1/merchant/checkout-sessions/by-reference/{reference}`. Don't rely on polling to fulfil orders: use webhooks.

A live key can only create sessions in currencies Payport has enabled for you. Anything else returns 400.

## 4. Money: balances, statement, payouts

| Call | Returns |
|---|---|
| `GET /api/v1/merchant/balances` | Per currency, net of Payport fees and refunds (see the buckets below). |
| `GET /api/v1/merchant/statement?currency=USD&from=2026-10-01&to=2026-10-31` | Payments, refunds and payouts with a running net. Ranges can be up to 366 days. |
| `GET /api/v1/merchant/payouts` | Each payout: `payoutId`, `amountMinor`, `status` (`SCHEDULED`, `INSTRUCTED`, `PAID`, `RETURNED`, `FAILED`), `bankReference`, `paidAt`. |

The balance buckets are:
- `pendingMinor`: inside the settlement hold;
- `availableMinor`: waiting for the next payout;
- `inSettlementMinor`: in a payout the bank hasn't confirmed yet;
- `paidOutMinor`: confirmed paid.

A test key sees sandbox money and a live key sees live money. In the dashboard, add `?mode=test` to see sandbox money.

## 5. Webhooks

Register an endpoint in the dashboard (`POST /api/v1/merchant/webhooks { "url": "https://..." }`). The signing secret is shown once.

Endpoint rules:
- it must use `https`;
- it must be on a public address (private, loopback and cloud-metadata addresses are refused);
- the port must be 443, 8443 or 1024 and above;
- redirects are not followed.

**Events**

| Event | When |
|---|---|
| `checkout_session.paid` | Payment completed |
| `checkout_session.failed` | The payment did not go through. A timed-out payment is never reported as paid later; it is refunded. |
| `payout.paid` / `payout.returned` / `payout.failed` | The bank's result for a payout |
| `ping` | A test event (`POST /webhooks/{id}/test`) |

**Body**

```json
{ "id": "evt_...", "type": "checkout_session.paid", "event": "checkout_session.paid", "livemode": false,
  "created": 1760000000, "data": { "checkoutSessionId": 42, "reference": "order-1001", "status": "PAID",
  "amountCents": 2500, "currency": "USD", "metadata": "..." } }
```

The data fields are also repeated at the top level for older integrations.

**Verify every delivery.** Each delivery carries these headers:
- `Payport-Signature: t=<unix seconds>,v1=<hex>`
- `Payport-Event-Id: evt_...`

To check them:
1. Split the `Payport-Signature` header on `,` and `=`.
2. Compute `HMAC_SHA256(secret, t + "." + rawBody)` as lowercase hex.
3. Compare it to `v1` in constant time.
4. Reject the delivery if `t` is more than 5 minutes away from your own clock.
5. Use `Payport-Event-Id` to ignore duplicates. Retries and replays keep the same id.

```js
const [t, v1] = header.split(',').map(p => p.split('=')[1]);
const expected = crypto.createHmac('sha256', secret).update(`${t}.${rawBody}`).digest('hex');
const ok = crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(v1)) && Math.abs(Date.now()/1000 - t) < 300;
```

Webhooks registered before v2 still receive `X-Payport-Signature: sha256=<hex of HMAC(body)>`. The header is still sent alongside v2.

**Delivery and retries.** Return any 2xx within 10 seconds to acknowledge a delivery. If you don't, Payport retries after 1 minute, 5 minutes, 30 minutes, 2 hours and 6 hours. After that the delivery is marked `FAILED`.

**Webhook console (dashboard)**

| Call | Purpose |
|---|---|
| `GET /webhooks/{id}/deliveries` | Delivery history |
| `POST /webhooks/deliveries/{deliveryId}/replay` | Resend a delivery (same event id) |
| `POST /webhooks/{id}/test` | Send a ping |
| `POST /webhooks/{id}/rotate-secret` | New signing secret |
| `POST /webhooks/{id}/disable` | Stop deliveries |

## 6. Sandbox

Sessions created with a test key never reach a real provider. The customer phone number entered on the hosted page picks the outcome:

| Phone ends with | Outcome |
|---|---|
| `0000001` | Declined: "Insufficient funds" |
| `0000002` | Declined: "Transaction cancelled by the customer" |
| anything else | Paid |

Sandbox payments produce real webhooks with `livemode: false` and appear in the test balances and statement. They are never settled.

## 7. Errors you should handle

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `CHECKOUT_SESSION_CREATE_FAILED` | Invalid amount or currency, a duplicate reference, or a currency not enabled for live |
| 401 | `API_KEY_EXPIRED` | Use the rotated key |
| 403 | `INSUFFICIENT_SCOPE` | The key lacks the scope, or the action is dashboard-only |
| 409 | `NOT_LIVE_READY` | Live keys aren't available yet (see `/readiness`) |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | The first request with this `Idempotency-Key` is still running; retry shortly |
| 422 | `IDEMPOTENCY_KEY_REUSED` | The same `Idempotency-Key` was used for a different request |

Send an `Idempotency-Key` header on every `POST` you might retry.
