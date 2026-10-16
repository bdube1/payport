# Penetration test scope (for the external tester)

_Phase 9. PayPort commissions an independent test before go-live and after any major change. This page is
the scope and the rules of engagement to send to the tester._

## In scope (staging environment only)

| Surface | Base | Authentication | What to look for |
|---|---|---|---|
| Customer API (web + mobile) | `/api/v1/auth/**`, `/transactions/**`, `/wallet/**`, `/beneficiaries/**` | customer JWT; per-transaction token (`X-Transaction-Token`) for guests | IDOR on references and wallet numbers, PIN brute force and lockout, transfer / payment double-spend, replay of idempotency keys |
| Hosted checkout and payment links | `/api/v1/checkout/*`, `/api/v1/pay/*` | public | session tampering, amount changes, QR payload forgery (`/qr/resolve`) |
| Merchant dashboard and API | `/api/v1/merchant/**`, `/api/v1/merchant-portal/**` | merchant JWT; secret API keys with scopes | scope bypass, test-mode key moving live money, tenant isolation between merchants, webhook SSRF |
| Ops and compliance console | `/api/v1/admin/**` | staff JWT, roles ADMIN / OPS / FINANCE / COMPLIANCE | role escalation, four-eyes bypass (self-approval), audit-log tampering |
| Callbacks and webhooks | `/api/v1/callbacks/**`, `/api/v1/whatsapp/webhook`, `/api/v1/ussd` | provider signatures / status-query confirmation | forged success callbacks, replay, signature bypass |
| POS terminal API | `/api/v1/pos/**` | HMAC + nonce + timestamp | replay, clock-skew abuse, terminal impersonation |
| Metrics and health | `/actuator/**` | scrape token for Prometheus | information disclosure |

Frontends (consumer web, merchant portal, ops console, mobile app builds) are in scope for client-side
issues (XSS, token storage, open redirects).

## Out of scope

Production; denial-of-service and volumetric tests; social engineering; the payment providers' and banks'
own systems (sandbox endpoints only, with their permission); physical attacks.

## Controls already tested in CI (the tester should try to break them)

- Authorization matrix: every `/api/v1/admin/**` route refuses anonymous callers and customers, every
  `/api/v1/merchant/**` route refuses customers (`SecurityResilienceScenarioTest`), and both prefixes are
  closed by URL as well as by method annotations.
- Wallet double-spend: 30 concurrent transfers against money for 10 → exactly 10 succeed (ledger row locks
  and a database no-negative rule).
- One payment, ten simultaneous wallet confirmations → taken once.
- POS replay (same nonce), stale timestamps and wrong signatures refused; WhatsApp unsigned deliveries refused.
- Self-approval refused by database CHECK constraints (approvals, FX rates, float deposits, settlement rules).
- Security headers: CSP `default-src 'none'`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, HSTS.

## Rules of engagement

Test accounts for each role are issued by PayPort for the test window and revoked after. Findings go to
PayPort's security contact within 24 h for critical issues; the final report rates findings by CVSS 3.1
and is re-tested after fixes.
