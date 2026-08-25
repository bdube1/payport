# Frequently Asked Questions & Troubleshooting

Direct answers to the questions customers actually ask, organised by
topic. Written so each Q&A pair can stand alone.

## Getting started

**Q: Do I need an account to pay for airtime or a bill?**
No — Payport Pay supports one-off payments without an account. Creating
an account (a phone number and 4-digit PIN) lets you save beneficiaries,
see your payment history, and speeds up repeat purchases.

**Q: What's the difference between the web app and the mobile app?**
They offer the same services and share the same account — sign in with
the same phone number and PIN on either. Use whichever is more
convenient; there's no feature you get on one that you can't get on the
other for consumer payments.

**Q: I forgot my PIN. What do I do?**
Contact Payport support (WhatsApp/phone +263 71 918 3557, or
info@payport.africa) to have it reset. After a reset, you'll be asked to
set a new PIN the next time you sign in before you can do anything else —
that's expected, not an error.

## Payments

**Q: My payment says "processing" and hasn't changed in a few minutes — is something wrong?**
Not necessarily. Payport actively re-checks with the payment provider
in the background when a response is delayed, rather than just waiting
passively — most delayed payments resolve within a few minutes without
you needing to do anything. Avoid submitting the payment again while
you're waiting.

**Q: I paid with Omari and nothing happened.**
Omari is different from EcoCash and OneMoney: it doesn't send a
phone-side approval prompt. Instead, check for an SMS with a one-time
code, and enter it on the processing screen in the app when prompted.
You have up to 3 attempts and the code expires after about 5 minutes —
if it's expired, you'll need to restart the payment to get a fresh code.

**Q: Money left my account/wallet but I didn't get my airtime/bill payment.**
This means the payment succeeded but delivery didn't complete — contact
Payport support with your transaction reference. This is exactly the
kind of case Payport's operations team monitors and resolves, either by
completing the delivery or issuing a refund.

**Q: How do I find my transaction reference?**
It's shown on the processing screen right after you make a payment, and
included in the confirmation. If you've lost it, signed-in customers can
find it in their History tab.

**Q: Can I pay in ZWG instead of USD, or vice versa?**
For services that support both currencies, yes — you'll be asked to
choose a currency as part of the payment flow. Not every service
supports both; the app will only show currencies actually available for
that specific service.

**Q: Do you accept JCB or UnionPay cards?**
Payport's terms of service list a broad range of supported card networks
(Visa, Mastercard, JCB, UnionPay, and others) as part of what Payport is
permitted to process. Visa and Mastercard are the card networks confirmed
to work in the card payment flow today — if a customer needs to know
whether a specific less-common card network is actively usable right
now, don't confirm or deny definitively; check with the Payport team.

**Q: I don't have an account with Payport — can I still track a payment I made?**
Yes — use "Track Order" and enter your transaction reference number.

## Account management

**Q: I lost my phone / it was stolen. What should I do?**
Contact Payport support immediately (+263 71 918 3557 or
info@payport.africa) so they can lock the account and reset access.
Since Payport Pay allows only one active session at a time, signing in
on a new device will already end any session on the lost phone — but
reporting it lets support secure the account properly and reset the PIN.

**Q: Can I close my Payport account and have my data deleted?**
Yes — this is a genuine data-rights request, not something Payport
Assistant can action directly. Direct the person to support@payport.africa.
Some transaction records may need to be retained for a period under
financial-record-keeping obligations even after an account is closed —
this is normal and not specific to Payport.

**Q: Can I use Payport from outside Zimbabwe — for example, to send money home?**
Payport Pay is built around Zimbabwean mobile money and payment rails
(EcoCash, OneMoney, Omari, ZimSwitch), so the payments themselves move
within that Zimbabwean payment ecosystem. Whether the app itself can be
used from abroad to initiate a payment for someone in Zimbabwe isn't
something to confirm or deny without checking — if asked, say this is
worth confirming directly with the Payport team rather than assuming
either way.

**Q: I have a dispute about a transaction — what happens?**
Payport has a formal dispute-handling process that Payport's operations
team manages, separate from routine refund cases. Report the issue with
the transaction reference via the contact details below (or, for
merchants, via the Merchant Portal's Queries section) and it will be
reviewed by a person, not resolved automatically.

**Q: What are Payport's support hours?**
Specific support hours aren't published in this knowledge base — rather
than guessing, say support is available via WhatsApp, phone, or email,
and response times may vary outside business hours, then give the
contact details.

## Merchants

**Q: How long does merchant approval take?**
This depends on the completeness of the KYC documentation submitted and
Payport's review queue — there's no fixed published turnaround. Use your
application reference number to check status, or contact Payport
directly if it's been a while.

**Q: What do I need to integrate Payport as a merchant?**
Depends on the approach: Hosted Checkout needs minimal integration work
(mostly just redirecting to a Payport-hosted page), while the direct API
needs development resources on the merchant's side. Payment Links need
no integration at all — just generate and share a link.

**Q: How do I know a webhook genuinely came from Payport?**
Every webhook Payport sends is signed with HMAC-SHA256, delivered in an
`X-Payport-Signature` header. Verify the signature against your own
webhook secret before trusting the payload.

## Payport Books / Docs / School

**Q: How do I create an invoice in Payport Books?**
From the Sales module, create a new invoice against a customer, add line
items, and send it — the system tracks whether it's been paid and, where
applicable, posts it to ZIMRA as part of the fiscalisation flow. Exact
navigation steps depend on the current interface; for a live walkthrough,
this is best shown directly rather than described from memory.

**Q: How do I approve a document in Payport Docs?**
A document routed for approval appears in the approver's review queue;
opening it shows the document and version history, with options to
approve, reject, or return it to the submitter for changes. Only users
with approval permission on that document/workflow will see this option.

**Q: How do I mark attendance in Payport School?**
Attendance can be recorded manually by a teacher against a class list, or
via a supported attendance device/QR-code check-in where the school has
that set up. Exact steps depend on the school's specific configuration.

**Q: Is [Books/Docs/School] fully live and production-ready?**
These are real, working products with substantial functionality built
and tested. They're newer than the core payments platform and are still
being actively hardened toward the same production maturity — be
straightforward about this rather than claiming a specific finished
state, and connect the person with the Payport team for a specific
organisation's rollout timeline.

**Q: Can Payport School's fee tracking connect to Payport Pay for actual payment collection?**
The systems are built by the same team and Payport School includes its
own payment- and wallet-related functionality; for a specific
integration question about a particular school's setup, connect the
person with the Payport team directly rather than assuming a specific
integration is live.

## General

**Q: Is Payport a bank?**
No — Payport is a payments and technology company. It integrates with
mobile money providers (EcoCash, OneMoney, Omari) and card networks
(via ZimSwitch) to move money on a customer's behalf; it doesn't hold
deposit accounts the way a bank does.

**Q: What if my question isn't answered here?**
Say so plainly and give the contact details rather than guessing:
WhatsApp/phone +263 71 918 3557, email info@payport.africa, or — for
existing app users — the in-app Help section.
