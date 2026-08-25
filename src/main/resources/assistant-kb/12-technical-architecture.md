# How Payport Is Built

This file is for more technical questions — how the systems fit together,
what technology is used, and how reliability is engineered. Most
customers won't ask this, but technical partners, developers integrating
with Payport, and curious users sometimes do.

## The systems behind Payport Pay

Payport Pay is made up of five parts that all talk to one central
backend:

| Component | What it's for |
|---|---|
| **The Gateway** | The central backend — every payment, delivery, and balance check passes through it. Nothing else has direct database access. |
| **Consumer Web App** | app.payport.africa — the browser-based version customers use |
| **Mobile App** | The iOS/Android app — same account, same features as the web app |
| **Merchant Portal** | merchant.payport.africa — where businesses manage their integration |
| **Ops Portal** | Payport's internal tool for staff — transaction monitoring, merchant KYC review, reconciliation, dispute handling, and settlement processing. Not customer-facing; this is how Payport's own team runs the platform day to day. |

Every one of these talks to the Gateway over an authenticated API — there
is no direct connection between, say, the mobile app and the database, or
between the merchant portal and the consumer app.

## Technology used

The Gateway is built in Java (Spring Boot) with a MySQL database.
Payport's web applications (Consumer, Merchant, Ops Portal) are built in
React; the mobile app is built with React Native; the marketing website
uses Astro. Payport Books' backend is also Java/Spring Boot; Payport Docs
is a Java-based document management backend with its own frontend;
Payport School's backend is Node.js (Express) with a React frontend.

## How Payport keeps payments reliable

Third-party payment and telecom systems occasionally time out or respond
ambiguously — this is normal for the industry, not specific to Payport.
A few things Payport does specifically to handle this well:

- **Duplicate-safe retries.** If a payment or delivery request might have
  been sent twice (e.g. due to a network hiccup), the payment or telecom
  provider recognises the duplicate and Payport checks the real outcome
  rather than risking charging or delivering something twice.
- **Active reconciliation, not passive waiting.** When a payment's status
  is unclear, Payport actively queries the payment provider for the real
  answer rather than just waiting for a callback that might not arrive.
- **A distinct "still resolving" state.** A delayed payment isn't
  immediately treated as failed — there's a specific state for "still
  being confirmed" that allows a late confirmation to still complete the
  payment successfully.
- **Full audit history.** Every transaction's status changes are logged,
  so any individual transaction can be reconstructed and reviewed.

## For developers integrating with Payport

- Authentication uses JSON Web Tokens (JWT) for portal users, and API
  keys for direct merchant integrations.
- Outbound webhooks are signed with HMAC-SHA256 (`X-Payport-Signature`
  header) so a receiving system can verify authenticity.
- See the Merchant Gateway file in this knowledge base for integration
  options (Hosted Checkout, direct API, Payment Links).

## Questions this file can't answer

Specific uptime/SLA commitments, infrastructure hosting details, and
detailed API reference documentation aren't covered here — for those,
route the person to the Merchant Portal's own resources or to Payport's
team directly rather than guessing at a number or a spec.
