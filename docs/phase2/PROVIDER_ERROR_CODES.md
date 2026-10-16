# Provider error codes and retry rules

Source documents (supplied by the owner, 2026-10-16):
- EcoCash Instant Payments API;
- OneMoney DPS APIs;
- O'mari Merchant API v1.3.0;
- Econet XML-RPC API v1.2.3;
- NetOne E-Top-Up Webservice 2.8.3;
- e-Solutions Bill Payments API v2.2;
- ZimSwitch (OPPWA) online documentation.

The rules are implemented in `DeliveryOutcomeRules` (deliveries) and `ZimSwitchAdaptor.classifyResultCode` (card payments), and unit-tested.

| Verdict | Meaning | What PayPort does |
|---|---|---|
| RETRYABLE | The provider definitely did not act, and the cause is temporary or is PayPort's own float | Automatic retry with back-off (30 s, 2 min, 5 min), at most 4 deliveries |
| UNKNOWN | The code does not say whether it happened | Never re-sent; resolved by the provider's status query, or by a person |
| FINAL | The provider definitely did not act, and a retry will not help | No retry; the customer sees the provider's message; ops refund |

## Deliveries

| Provider | RETRYABLE | UNKNOWN (query) | Everything else | Status query |
|---|---|---|---|---|
| Econet (7.1) | 8200 External Temp Error; 905 Insufficient Credit; 8104 Insufficient Funds (PayPort's float); 8209 Out of Stock | 990 Request Timeout; 999 General Error; 8060 Duplicate; 8061 Pending; 8201/8202 External System/Technical; 8205/8207 Processing Error; 8206 Already Processed; 8208 In Progress; 8210 External Timeout; 8211 Already Submitted | FINAL | `get_transaction_status` (3.6), **now implemented** (it previously returned "not implemented") |
| NetOne (16) | 206 platform down; 208 insufficient wallet (float); 209 stock; 210 "try again later"; 702 webservice unavailable | 216 duplicate; 219 web exception; 312 received/processing; 700 timeout; 701 connection issue; 703/704 errors; query codes 0 pending and 1 busy | FINAL (217 wrong number, 222 limits, ...) | `query-transaction` (9). Code 3 "not found" means the request was never received, so it is safe to send. Each query now uses a fresh agent reference, because a reused reference is rejected with HTTP 429. HTTP 400 replies are now read as NetOne's answer |
| e-Solutions: ZESA, ZOL, Nyaradzo, Moonlight, Bulawayo (4.2) | 51 vendor out of credit (PayPort's float) | 09 in progress; 94 duplicate | FINAL. The spec says "all other codes indicate a failed transaction"; 68 is a failure like any other | Resend/lookup (4.2.2), every 5 minutes while unknown, as the spec requires |
| Telecel, merchant checkout | none (no code table supplied) | adaptor-flagged transport errors only | FINAL | Telecel: a failed query is now UNKNOWN, not FAILED |

A delivery that provably never left PayPort (connection refused, unknown host, connect timeout) is retried automatically for every provider.

## Payments

These specs document no error codes. A payment is never retried automatically: the customer starts a new attempt.

| Provider | Final states in its status query |
|---|---|
| EcoCash | COMPLETED, FAILED; pending: PENDING SUBSCRIBER VALIDATION |
| OneMoney | 30 success, 40 failure, 50 order closed; pending: 10, 20 |
| O'mari | responseCode 000 = success; the OTP request answers synchronously |
| ZimSwitch / OPPWA | Result code groups. SUCCESS: 1.1, and 1.2 (review). PENDING: 1.3, 1.4, and 200.300.404 (checkout not paid yet). UNKNOWN: 900.100.300/400/500, 100.396.104, 999.*. FAILED: only the documented rejection groups. Status queries are limited to 2 per minute per checkout. Webhooks are AES-256-GCM encrypted; decryption is now implemented (`ZIMSWITCH_WEBHOOK_SECRET`) |

## Customer messages

The customer sees the provider's own text:
- EcoCash, NetOne and Econet: the `Description` / `ReplyMsg`;
- e-Solutions: the narrative ("display the given narratives as they are given");
- OPPWA: the result description;
- O'mari: its `message`.

PayPort writes its own words only where the provider gave none:
- "Could not reach EcoCash. No money was taken.";
- "The transaction timed out.";
- "No response from EcoCash. The transaction timed out.".
