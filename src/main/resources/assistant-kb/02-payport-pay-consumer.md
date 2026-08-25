# Payport Pay — Consumer Payments

Payport Pay is Payport's consumer-facing payments product — available as
a web app (app.payport.africa) and a mobile app (iOS and Android). It lets
anyone in Zimbabwe pay for airtime, mobile data, and bills using mobile
money or card, without needing to queue or visit an agent.

## What you can pay for

- **Airtime** — top up any Zimbabwean mobile number, on Econet, NetOne, or
  Telecel. The app automatically detects which network a number belongs to.
- **Mobile data bundles** — Econet, NetOne, and Telecel data bundles.
- **Bills** — ZESA (prepaid electricity tokens), DStv/GOtv subscription
  renewals, TelOne, ZINWA, and other biller accounts (Nyaradzo funeral
  policies, Moonlight Life Assurance, ZOL internet, and more) via Payport's
  bill-payment integrations.

You don't need an account to make a one-off payment, but creating one
(Register/Login) lets you save beneficiaries, see payment history, and
speeds up repeat purchases.

## How to pay for something

1. Choose the service (Airtime, Data, or Bills) from the home screen.
2. Enter the recipient's number or account (for airtime/data, the network
   is detected automatically from the number).
3. Choose the currency (USD or ZWG, where the service supports both) and
   the amount.
4. Choose a payment method and enter the wallet number the payment will
   come from.
5. Confirm. You'll be taken to a processing screen that shows live status
   while the payment and delivery complete.

## Payment methods supported

| Method | How it works |
|---|---|
| **EcoCash** | Approve a USSD/app prompt on your phone after confirming in Payport Pay |
| **OneMoney** | Approve the prompt on your phone the same way |
| **Omari** | Different from the others: Omari sends you a one-time code by SMS, which you type into Payport Pay's confirmation screen — there's no phone-side approval prompt for Omari. You have up to 3 attempts to enter the code correctly, and the code is time-limited (about 5 minutes) |
| **ZimSwitch (card)** | Pay with a Visa or Mastercard through a secure card entry screen |

## What the processing screen shows

After you confirm a payment, the screen updates live through these
stages: order created → awaiting payment → payment confirmed → processing
→ delivered. Most payments complete within seconds to a couple of minutes.
If a payment method needs an extra step (like Omari's SMS code), the
processing screen will prompt for it directly — you don't need to leave
the app.

## "My payment seems stuck — what do you do about it?"

This is one of the most common support questions, so it's worth answering
in detail:

- **If you're on the processing screen and it's still checking:** this is
  normal for up to a few minutes, especially if the mobile network is
  slow to respond. The app keeps checking automatically — you don't need
  to retry the payment yourself.
- **If it says "still confirming" for longer than a few minutes:** Payport
  runs an active reconciliation check with the payment provider in the
  background for these exact cases — it isn't just waiting passively for
  a response that might never come. Genuinely stuck payments are rare and
  usually resolve within minutes without any action needed from you.
- **If a payment ultimately fails:** nothing is charged if the payment
  step itself failed. If payment succeeded but the airtime/data/bill
  wasn't delivered, Payport's operations team can see this and will
  refund you — reach out via the contact details below with your
  transaction reference.
- **Never submit the same payment twice** while one is still processing —
  wait for it to reach a final status first.

## Finding a past transaction

- **Signed in:** the History tab shows your full transaction record.
- **Not signed in / need to check one specific payment:** use "Track
  Order" and enter the transaction reference (format:
  `PP-MOB-YYYYMMDD-XXXXXX` or similar) — this reference is shown on the
  processing screen and sent to you after a successful payment.

## Beneficiaries

Signed-in customers can save frequently-used recipients (a phone number
for airtime, or a biller account) as beneficiaries, so future payments to
the same person or account don't require re-entering the details each
time.

## Refunds

If a payment succeeded but the underlying service wasn't delivered,
Payport's operations team handles the refund manually and records it
against the transaction with a reference for what was returned and how.
This is a deliberate, audited process — refunds aren't instant or
automatic for most payment methods, so allow some time for the Payport
team to process one once reported.
