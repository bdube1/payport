# Payport Commerce: status

**Current phase:** 2a (stock, cart, pickup or delivery, customers), on top of Phase 1 (links, product pages, mini-store, sharing, PSP events). Built 31 October 2026.

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

## Phase 2a
- Stock per product (taken when the PSP confirms payment, returned on cancellation; an order paid as an item sold out is flagged).
- Cart (several items, one store and currency), pickup or delivery with a fee and free delivery above an amount, delivery steps
  (preparing, ready or out, collected or delivered), and the merchant's customers from paid orders.

## Review of Phase 2a (31 Oct 2026), acted on
- Stock is reserved atomically when the order is placed (never two buyers for the last item), sold on PSP confirmation,
  released only when the payment definitively did not happen or the order is cancelled; uncertain payments keep it;
  orders that never reached the PSP give it back after 35 minutes. Tested, including the competing checkout.
- WhatsApp results: one per payment (keyed by payment reference, checked before sending); the window close does a last
  provider query and a definite provider answer wins over the timer.
- Customers are computed from paid orders only, amounts net of refunds paid out.

## Phase 2b
- Three fixed designs (Product, Boutique with categories, Local business), banner and tagline.
- Custom domains: DNS TXT proof, certificates only for verified domains (Caddy on-demand TLS asks Commerce), one store per
  domain, removal stops certificates and routing.
- Delivery through a provider adapter (MANUAL now; a courier is one more adapter when a provider with an API is chosen).

## Not built yet
- Courier integrations (waiting for a chosen provider), product variants.
- Real-server acceptance run of the full Commerce journey (next milestone).
- Refund of a payment already paid out to the merchant (needs a settlement adjustment).
- Merchant webhooks moved onto the event stream.

## Risks
- The staging server's memory: Commerce adds about 300 MB.
- PCI: Commerce sends customers to the PSP's hosted checkout only; the consumer site's card widget scope (SAQ A-EP)
  is unchanged and still to be confirmed with the QSA.
