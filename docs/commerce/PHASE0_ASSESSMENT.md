# Payport Commerce and Payport Intelligence: Phase 0 assessment

30 October 2026. Read from the code on `main` (gateway at commit 2ae14fc, plus the four portal and mobile repositories).
No feature code was written for this phase. Terms follow the brief: *implemented* = code exists; *tested* = covered by
an automated test that ran in CI; *runtime verified* = observed working on a running system.

## 1. Architecture and stack, as found

| Area | What the code shows |
|---|---|
| Backend | One Spring Boot 4.1.1 application on Java 21 (the "gateway"), already a modular monolith by package: payments, ledger, wallets, merchant, settlement, recon, risk, billers, cards, agents, USSD, WhatsApp, customer care, observability. |
| Data | MySQL 8, Flyway migrations V1 to V67, JdbcTemplate with some JPA. The ledger is double-entry in stored procedures (`ledger_post`, `ledger_reverse`) with hash-sealed entries and an integrity report. Money is minor units plus currency (no floating point in the ledger). |
| Jobs | Scheduled jobs with database leases (`platform_job_leases`), so two instances never run the same job. |
| Frontends | React 18 + Vite: consumer site (`app.`), merchant portal (also hosts the agent portal), ops console. Mobile: Expo 54 / React 19. |
| Channels | Web, app, USSD (*699#, three network gateways plus an aggregator), WhatsApp Cloud API, POS, merchant API. |
| Hosting | One Ubuntu VM per environment behind Caddy (TLS). Staging is at `*-staging.payport.africa`. |
| Tests | GitHub Actions with Testcontainers MySQL: 364 tests on main; 258 run in the main job and the 106 ledger tests run against a real MySQL in their own job. Production-like migration and dependency-scan jobs. |

This matches the brief's assumed stack (Java/Spring Boot, MySQL, React).

## 2. What Commerce can reuse

| Need | Exists in the PSP | Gap |
|---|---|---|
| Merchant model | `merchants`, `merchant_users` with roles, KYB cases, settlement destinations, merchant codes and EMV QR | None for Phase 1 |
| Authentication | Merchant portal login (JWT); API keys with scopes (`checkout`, `refunds`, `payment_links`, `webhooks`…); idempotency keys on create calls | A per-store key or service identity for Commerce (see 3.4) |
| Payment API | Checkout sessions with success URL, metadata and the PSP-hosted checkout page (`app./checkout/:id`) | Order reference and line-item summary on the session |
| Payment links | Implemented: slug, fixed **or** open amount, description, reusable flag, disable | Expiry, merchant reference, product link |
| Webhooks / events | Signed (HMAC with timestamp), retried, merchant-managed; events `checkout_session.paid`, `checkout_session.failed`, `payout.*` | No general event stream; no events for uncertain outcome, refunds, disputes, settlement |
| Refund API | Merchant partial refunds per checkout session (`RefundService`) | Refund "completed" event |
| Transaction status | One customer-facing outcome per payment, including "uncertain" (`TransactionOutcomeService`) | None |
| Ledger, settlement, reconciliation | Complete and tested; Commerce never touches them | None |

## 3. What is missing, and what I would add on the PSP side

1. **Event outbox.** The PSP has purpose-built outboxes (push, merchant webhooks, WhatsApp) but no general event
   stream. Add one `platform_events` outbox (stable ID, time, merchant, type, version, source, payload without card
   data or unnecessary personal data), written in the same database transaction as the change it records, with a
   dispatcher and idempotent consumers (`processed_events`). This is the foundation the brief asks for in Phase 1, and
   it is also what Payport Intelligence will learn from.
2. **Payment events.** Emit initiated, confirmed, failed, outcome uncertain, reversed, refund requested/completed,
   dispute opened/resolved, settlement created/reconciled into that outbox; merchant webhooks then read from it.
3. **Payment links v2.** Add expiry, merchant reference and an optional product link to the existing links
   (additive migration, existing links keep working).
4. **A Commerce facade.** One documented interface (`PspPaymentsApi`: create checkout, create/disable link, request
   refund, read status) that Commerce calls; nothing in Commerce may read PSP tables.

No PSP redesign is needed. Each of these is additive and reversible.

## 4. Security and integration risks

| Risk | Impact | Recommendation |
|---|---|---|
| Card acceptance uses the ZimSwitch/OPPWA "copy and pay" widget, which renders the card fields from the provider's script inside Payport's own consumer page | PCI DSS scope for the consumer site is likely SAQ A-EP, not SAQ A: the page that loads the script must be secured like a payment page | Keep it; Commerce storefronts must always redirect to the PSP-hosted page and never embed card fields. Confirm the SAQ type with your QSA. |
| Card issuing: "show card details" returns the full card number and CVV from the issuer through Payport's servers | Those components are in full PCI DSS scope | Before live cards, switch to the issuer's hosted (iframe/token) reveal so the PAN never passes through Payport |
| The website assistant sends visitors' messages to Google Gemini (free tier, 900/day cap) | Visitors can type personal data; no data-processing terms on a free tier | Decide the AI provider and data rules before Payport Intelligence (decision 4) |
| Tenant isolation is enforced per endpoint by merchant ID from the token | Commerce adds many tenant tables; one missed filter leaks another merchant's data | Tenant-scoped repository layer plus automated cross-tenant tests for every Commerce endpoint |
| Merchant refunds through the API do not go through the new central reversal desk | Two routes for money going back | Decision 2 |
| Staging server memory (builds already needed extra memory) | Commerce, the observability stack and the PSP will not fit on one VM | A second VM for monitoring (and later Commerce staging) |
| Webhook targets | SSRF guard and signing exist | No change |

## 5. Commerce architecture

- **Where it runs.** Inside the same Spring Boot application as a separate module (`zw.co.payport.commerce.*`),
  matching the brief's modular monolith. The boundary is enforced, not just intended:
  - Commerce has its own database schema (`payport_commerce`) and database user with **no rights** on PSP tables;
  - an automated architecture test fails the build if Commerce imports PSP repositories, the ledger or JdbcTemplate
    for PSP tables;
  - Commerce calls the PSP only through `PspPaymentsApi`.
- **Modules.** Links and social sharing (Phase 1); catalogue, inventory, cart, orders, customers, delivery adapter
  (2a); storefront templates and domains (2b); events (shared).
- **Payments.** An order creates a PSP checkout session (amount, currency, order reference) and the customer is
  redirected to the PSP-hosted checkout. The order stays unpaid until the PSP's `payment.confirmed` event arrives.
  Order state and payment state are separate fields.
- **PSP to Commerce.** Outbox events, consumed once each (duplicate and late deliveries are ignored safely).
- **Table ownership.** The PSP owns transactions, checkout sessions, refunds, ledger, settlement and reconciliation.
  Commerce owns stores, products, inventory, carts, orders, store customers and link presentation. The events module
  owns the append-only outbox.
- **Storefront.** A new React app on `store.payport.africa` (custom domains in 2b) using fixed templates.

## 6. Phase 1 plan

**Build**
1. Event foundation: `platform_events` outbox, dispatcher, idempotent consumers, replay, retention.
2. PSP payment events into the outbox (section 3.2); merchant webhooks read from it.
3. Payment links v2: fixed or open amount, description, reference, optional expiry; product-specific links.
4. Shareable product page and a simple branded mini-store with direct (single-item) checkout.
5. Sharing: WhatsApp, Facebook, X, email, copy-link, QR.
6. Merchant portal: links and products, payment status from the PSP, sales overview.

**Acceptance tests** (real MySQL, in CI)
- A link is paid only when the PSP confirms; a duplicate or late confirmation changes nothing; an uncertain
  outcome shows "being confirmed" and is never shown as paid.
- An expired or disabled link refuses payment; an open amount respects the product limits.
- Repeated clicks and retries create one checkout (idempotency).
- Every payment state change produces exactly one event with a stable ID; replaying events changes nothing.
- A refund request stays "requested" until the PSP confirms it.
- Merchant A cannot read, change or pay into merchant B's links, products or events (API and IDs).
- Clean install and upgrade from the current schema.

**Success measures** (computed from events)
- Time from a new merchant's first login to the first shared link.
- Share of links with a started checkout that end in a confirmed payment.
- Merchants creating or sharing a link in the first month.

## 7. AI and machine learning: what I recommend, and what I would adjust

Your message asks to embed AI and machine learning now; the brief puts the AI features in Phase 3 (after the store
is live) and machine learning in Phase 4 (only with enough data and a measured benefit over rules). Both can be
true if we split the work like this:

**Now (Phase 1, no AI yet, but everything AI needs later)**
- The event foundation above. Without clean, labelled history there is nothing to learn from.
- Labels from operations: complaint categories, reversal reasons and outcomes, and risk-alert outcomes are already
  recorded by the new customer-care desk; we keep them consistent so they can train and evaluate models.
- AI guard-rails as platform code: per-feature usage limits, cost metering, prompt/response logging without personal
  data, and a kill switch per feature. The Control Centre gets a "Payport Intelligence" panel (usage, cost, errors).

**Early PSP-side assistants (behind feature flags, read-only, human approves)**
1. *Complaint triage*: suggests the complaint type and priority from the description; staff confirm.
2. *Ops investigation helper*: explains a trace in plain words and drafts the customer reply; it cannot request or
   approve reversals (brief section 9).
3. *Fraud scoring in shadow mode*: a model scores transactions next to the existing risk rules, never acting, until
   it beats the rules on a defined baseline (precision and recall on confirmed fraud).

**Later, as the brief says**: listing assistant, merchant assistant, search, shopping assistant (Phase 3);
recommendations, demand forecasting, routing optimisation (Phase 4).

## Decisions I need from you

1. **Commerce placement:** a module inside the gateway with its own database schema and user (recommended), or a
   separate service.
2. **Merchant refunds:** keep merchant-initiated refunds through the API (their own customers, their own balance)
   but list them in the central reversal register (recommended), or force them through the staff reversal desk.
3. **AI sequencing:** start the three early PSP-side assistants behind flags while Phase 1 is built (recommended), or
   wait for Phase 3.
4. **AI provider and data rules:** which model provider (the website assistant uses Google Gemini today), and the rule
   that no customer personal data goes into prompts unless a feature needs it and you approve it.
5. **A second server** for monitoring and Commerce staging.
6. **Storefront address:** `store.payport.africa` for mini-stores until custom domains (2b).
