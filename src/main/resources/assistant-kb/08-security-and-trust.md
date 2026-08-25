# Security & Trust

This file answers questions about how Payport protects customer data and
money. It's written to be useful for both everyday customers ("is my
money safe with Payport?") and more technical questions from partners or
security-conscious users.

## How Payport protects your account

- **PINs and passwords are never stored in readable form.** They're
  hashed (BCrypt) before storage, so even Payport staff cannot see a
  customer's actual PIN.
- **Sessions expire.** Login sessions (access tokens) expire after 1 hour
  and need refreshing; refresh sessions last up to 30 days.
- **Account status is checked on every action, not just at login.** If an
  account is deactivated or a PIN reset is forced, that takes effect
  immediately — not just the next time someone tries to log in.
- **One active device at a time on mobile.** Logging in on a new device
  ends the session on the previous one automatically.
- **Traffic is encrypted.** All traffic to Payport's apps and API travels
  over HTTPS.

## How Payport protects your money

- Payment confirmations for EcoCash and OneMoney require approving a
  prompt directly on the customer's own phone — Payport never asks for
  or has access to a customer's mobile money PIN.
- Every payment provider integration includes duplicate-protection: if a
  request to charge or deliver a service is ever sent twice by mistake
  (for example, due to a network hiccup), the provider itself recognises
  and rejects the duplicate rather than processing it twice.
- Payment outcomes that are genuinely ambiguous (a timeout, an unclear
  response) are actively re-checked against the payment provider's own
  records before Payport concludes success or failure — the system
  doesn't guess.
- Every transaction's full history — every status change, and what
  triggered it — is logged and auditable, so any individual transaction
  can be reconstructed and reviewed if there's ever a question about it.

## For merchants and technical partners

- Outbound webhooks are cryptographically signed (HMAC-SHA256), so a
  merchant's system can verify a webhook genuinely came from Payport and
  wasn't tampered with in transit.
- Role-based access control is enforced at the system level (not just
  hidden in the interface) — different staff roles, and different
  merchant team members, only have access to what their role requires.
- Where a payment involves sensitive data in transit to a provider (for
  example, OneMoney's required encryption scheme), Payport implements
  the provider's own specified encryption standard for that channel.

## What Payport is honest about

No system is without areas still being matured, and Payport would rather
say so than overclaim:

- Automated, instant refund reversal isn't yet available for every
  payment method — where it isn't, refunds are completed manually by
  Payport's operations team and recorded with a clear reference, which
  takes a bit longer than an instant automatic reversal would but is
  fully tracked.
- Payport Books, Payport Docs, and Payport School are newer products
  still being hardened toward the same maturity level as the core
  payments platform — see each product's own file in this knowledge
  base for specifics.

If someone asks a security question this file doesn't directly answer
(for example, a specific compliance certification, or a detailed
technical audit question), don't guess — offer to connect them with the
Payport team directly.
