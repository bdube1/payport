# PayPort Commerce: status

**Current phase:** 1 (links, product pages, mini-store, sharing, PSP events). Built 31 October 2026.

## Built
- PSP: platform event stream (payments, checkouts, refunds), Commerce API with service key, merchant identity for
  Commerce sign-in, merchant refunds from their own balance listed in the central reversal register.
- Commerce service (`commerce/`): stores, products with pictures, payment links (fixed or open amount with limits,
  reference, expiry, single use, product links), product pages and mini-store, sharing (WhatsApp, Facebook, X, email,
  copy, QR), orders with separate order and payment states, refunds, overview and success measures.
- Merchant portal: Store section.

## Tests
- PSP: `OperationalReadinessScenarioTest` order 4 (service key, one checkout per order, paid only when confirmed, one
  event per change, cursor, refunds from balance and payout, merchant isolation).
- Commerce: `CommerceScenarioTest` (three scenarios against real MySQL and a stand-in PSP, including the PSP down).
- Results are in the CI runs of the pull request; nothing here is claimed as passed unless CI ran it.

## Not built yet (later phases or decisions)
- Inventory, cart, delivery, store templates and custom domains (Phase 2).
- Refund of a payment already paid out to the merchant (needs a settlement adjustment).
- Merchant webhooks moved onto the event stream.

## Risks
- The staging server's memory: Commerce adds about 300 MB.
- PCI: Commerce sends customers to the PSP's hosted checkout only; the consumer site's card widget scope (SAQ A-EP)
  is unchanged and still to be confirmed with the QSA.
