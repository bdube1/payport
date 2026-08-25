# Payport Pay — Merchant Gateway & API

Businesses use Payport as a payment gateway to accept payments from their
own customers — through a hosted checkout page, a direct API integration,
shareable payment links, or a combination of these. This is the same
underlying payments infrastructure that powers the consumer Payport Pay
app, offered to merchants as an integration.

## How a business gets started

1. Apply via the Payport website (Merchant sign-up), submitting KYC
   documentation as part of the application.
2. The application receives its own reference number, which can be used
   to track review status independently of having an account yet.
3. Payport's operations team reviews the application and documentation.
4. Once approved, the business gets access to the Merchant Portal
   (merchant.payport.africa) to generate API keys, configure webhooks,
   and start integrating.

If a merchant application is taking a while or the applicant wants to
check status, they can use their application reference number on the
Merchant Portal's application-status page, or contact Payport directly.

## Ways to integrate

| Method | Best for |
|---|---|
| **Hosted Checkout** | Businesses that want a ready-made, Payport-hosted payment page — minimal integration work |
| **Direct API** | Businesses with development resources who want full control over the payment flow in their own product |
| **Payment Links** | No-code — generate a shareable link for a specific amount and send it directly to a customer |

## What the Merchant Portal provides

- **Dashboard** — transaction volume and status at a glance
- **Transactions** — full history of the merchant's payment activity
- **API Keys** — self-service generation and management for direct integration
- **Webhooks** — real-time notifications when a transaction's status
  changes, so a merchant's own system stays in sync without polling.
  Every webhook is cryptographically signed (HMAC-SHA256) so the
  receiving system can verify it genuinely came from Payport
- **Payment Links** — generate and manage shareable payment links
- **Settlements** — history of payouts from Payport to the merchant
- **Reports** — downloadable statements
- **Queries** — a direct support channel to Payport's operations team,
  including file attachments, for anything that needs a human
- **Team** — multiple users per merchant account, with owner/developer
  role separation

## Payment methods a merchant's customers can pay with

The same methods available on the consumer side: EcoCash, OneMoney,
Omari, and ZimSwitch card payments (Visa/Mastercard).

## Settlement

Merchants are settled periodically — Payport pays out the net proceeds
of processed transactions (after fees) to the merchant. Exact settlement
schedules and fee structures are commercial terms agreed directly with
each merchant, not published publicly; direct pricing questions to
Payport's team.

## Fees

Payport does not publish a public fee schedule. Fees are transaction-based
and discussed directly as part of onboarding — if asked for exact
numbers, say pricing is discussed directly with the Payport team rather
than quoting a figure.
