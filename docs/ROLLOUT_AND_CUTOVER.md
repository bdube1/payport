# Rollout and cutover plan

The PSP is designed to go live **underneath the running gateway**. Collections keep working throughout. What changes is that every movement is now also posted to the ledger, and settlement moves from the old run to the engine.

## 0. Before anything: build and test locally

This branch was compile-checked against API stubs, because the build environment couldn't reach Maven Central. Before deploying anywhere, run:

```
mvn -q verify
```

Then start the app against a **copy of production data** and confirm that Flyway applies V32 to V40 cleanly.

## 1. Database prerequisites

- MySQL 8.0.16+ (CHECK constraints are enforced).
- The append-only triggers need `log_bin_trust_function_creators=1` when binary logging is on (managed MySQL: set it in the parameter group), **or** apply V32/V33 as a user with SUPER/SYSTEM_VARIABLES_ADMIN.
- Take a backup, and test restoring it, immediately before migrating (see the restore test in the runbook).

## 2. Configuration (all in `.env.example`)

**Required:**
- `PAYPORT_DATA_KEYS`, `PAYPORT_DATA_KEY_ACTIVE`, `PAYPORT_INDEX_KEY`: field encryption keys. Generate them in your secrets manager, not on a laptop.
- `PSP_OPS_EMAIL`, `PSP_COMPLIANCE_EMAIL`.
- `PSP_SETTLEMENT_CASH_USD` / `PSP_SETTLEMENT_CASH_ZWG` and `PSP_TRUST_ACCOUNTS`: the ledger codes of your trust (safeguarding) accounts.

**Only when the channel goes live:**
- USSD: `PSP_USSD_CALLBACK_TOKEN`, `PSP_USSD_ALLOWED_IPS`.
- WhatsApp: the `WHATSAPP_*` variables.

**Must stay false in production:** `PSP_SANDBOX=false`.

## 3. Go-live configuration checklist (business decisions; set through the admin APIs)

Every seeded value in V40 is a placeholder.

- [ ] **Tax rates**: VAT on fees and IMTT (rate, cap, threshold, who bears it) per the current Finance Act. `POST /api/v1/psp/admin/tax/rates`, approved by a second finance user. *Until this is done, no VAT or IMTT is charged.*
- [ ] **KYC tier limits**: set to your RBZ e-money licence conditions (`psp_kyc_tier_limits`).
- [ ] **Wallet tariffs and agent commissions**: your published tariff (`psp_wallet_tariffs`).
- [ ] **Fee agreements per merchant**: without one, the gateway's existing commission rate applies.
- [ ] **Routing costs and bands**: from your contracts with each rail (`PUT /api/v1/psp/admin/payments/routing/rules`).
- [ ] **Fraud rule thresholds**: tune after two to four weeks of shadow data (`PATCH /api/v1/psp/admin/risk/rules/{code}`).
- [ ] **FX**: enter the first official ZiG/USD rate (`POST /api/v1/psp/admin/fx/rates`, four-eyes).
- [ ] **Sanctions lists**: load UN and OFAC (feeds or manual upload), plus your local designations and PEP list.
- [ ] **Staff roles**: give compliance officers `users.role = 'COMPLIANCE'`, and make sure no one person holds every role they would need to approve their own requests.
- [ ] **Regulatory report formats**: confirm the RBZ/FIU/ZIMRA layouts with each authority and adjust `ReportingService` if they differ.
- [ ] **Public transaction lookup**: close or token-protect `/api/v1/transactions/*` (see the architecture doc).

## 4. Phased rollout

| Phase | What runs | Exit criterion |
|---|---|---|
| **A. Shadow ledger** (1–2 weeks) | Migrations applied; every payment posts to the ledger; old settlement still pays (`payport.legacy.settlement.enabled=true` temporarily); engine drafts batches but nobody approves them | Ledger balances match the old settlement amounts per merchant for 5 consecutive business days; integrity job clean; zero DEAD outbox events |
| **B. Cutover** | Carry each merchant's unsettled gateway sessions into the ledger as opening balances (`POST /api/v1/psp/admin/merchants/{id}/cutover-requests`, four-eyes). Then turn off the old run (`payport.legacy.settlement.enabled=false`) | Every active merchant cut over; trial balance balances; safeguarding report shows customer funds ≤ trust balances |
| **C. Engine settlement** | Daily batches through the engine; bank file to your bank; proof on every instruction | One full week of batches with zero unexplained recon breaks |
| **D. Wallets and agents** | Open wallets to staff, then a pilot customer group, then everyone; onboard pilot agents with small float limits | Pilot limits held; no AML or fraud backlog |
| **E. Channels** | USSD short code, WhatsApp number, partner keys, POS terminals, one at a time | Per channel: signed-request failures understood, no unauthenticated traffic |

## 5. How cutover works (phase B)

For a merchant, `LedgerCutoverService` finds the gateway checkout sessions that are paid but unsettled and have no `COLLECT:` ledger entry yet. It computes the net amount the same way the old settlement did and raises a `CUTOVER_OPENING_BALANCE` approval.

When a second finance user approves:
- one balanced `OPENING_BALANCE` entry credits the merchant's available balance against opening-balance equity;
- those sessions are recorded in a gateway `settlements` row with status `MIGRATED_TO_LEDGER`, so neither system can pay them again.

The entry's idempotency key is per merchant and currency (`OPENING:<merchant>:<currency>`), so an opening balance can be posted only once. Sessions confirmed after the PSP release already have a `COLLECT:` entry and are never included.

**Rollback:**
- Before phase B: set `payport.legacy.settlement.enabled=true` and ignore the ledger. Nothing has been paid from it.
- After phase B: reverse the opening-balance entries (four-eyes `LEDGER_REVERSAL`) and set the `MIGRATED_TO_LEDGER` rows back to `PENDING`, under a change ticket.

## 6. Daily operations once live

- **Morning:** check the recon break summary, unresolved payments older than 30 minutes, dead outbox events, and the overnight integrity result.
- **Settlement:** review the drafted batches, submit them, have a second person approve, dispatch or export, then confirm paid with proof.
- **Daily safeguarding report:** reviewed by finance.
- **Monthly:** the RBZ NPS return, ZIMRA IMTT and VAT, and the FIU CTR file. Each is generated, reviewed by a second person, then marked submitted.
