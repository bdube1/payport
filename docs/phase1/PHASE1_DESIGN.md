# Phase 1: Financial foundation, design

_Status: approved scope (roadmap doc 08, approved 2026-10-15). The chart of accounts in §4 is a **proposal awaiting your finance lead's sign-off**; it can change without code changes beyond the category list._

## 1. Goal and exit criteria (from doc 08)

| # | Exit criterion | How it will be evidenced |
|---|---|---|
| E1 | 50+ scripted scenarios produce balanced entries per currency; balances equal the sum of lines | Scenario suite against real MySQL 8 (§9) |
| E2 | 200 parallel debits against one balance never overdraw and never double-post | Concurrency test against real MySQL 8 |
| E3 | Kill/restart during posting leaves no partial entry | Connection-kill test against real MySQL 8 |
| E4 | App DB user cannot TRUNCATE, ALTER, DROP TRIGGER or write journal rows directly | Privilege test against real MySQL 8 |
| E5 | Shadow ledger matches legacy merchant figures for a week of production-copy data, or every difference is explained | Comparison report (§8); **needs a production data copy (open item)** |
| E6 | Supported Spring Boot line | **Needs CI** (Maven blocked here); carried as an open item |

## 2. Shape

A new module, `zw.co.payport.gateway.ledger`, inside the gateway (ADR-0001). It does not reuse the `psp.*` packages directly (ADR-0002); the PSP branch's posting-rule ideas are harvested and re-expressed here.

```
gateway events ──► ShadowLedgerListener (flag, after commit, never throws)
                        │ uses
                        ▼
                PostingTemplates (versioned, pure Java)  ──►  JournalRequest
                        │
                        ▼
                LedgerClient (JDBC)  ── CALL ledger_post(...) ──►  MySQL (definer procedures)
                                                                    ├ validates + locks + writes
                                                                    └ app user: EXECUTE + SELECT only
```

## 3. Reference data (multi-country from day one, ADR-0006)

| Table | Content | Seed |
|---|---|---|
| `ref_currencies` | ISO 4217 code, minor units, name, enabled | USD (2), ZWG (2); others can be added by migration/config |
| `ref_countries` | ISO 3166 alpha-2, name, enabled | ZW |
| `ref_legal_entities` | code, legal name, country, base (reporting) currency, status | `PAYPORT_ZW` (legal name to be confirmed by you) |

Ledger accounts reference the currency and entity tables by foreign key, which replaces the hard-coded `USD/ZWG` CHECK from the old branch.

## 4. Chart of accounts (proposal: needs finance sign-off)

Each account is identified by **(legal entity, book, category, owner, currency)**. `book` is `LIVE` or `TEST` (ADR-0007): test-mode activity has its own book and can never mix with live money.

| Category | Type | Normal side | May go negative | Owner | Meaning |
|---|---|---|---|---|---|
| `BANK_CASH` | Asset | Debit | Yes | bank account code | Money in PayPort's (trust) bank accounts |
| `PROVIDER_RECEIVABLE` | Asset | Debit | Yes | provider code (ECOCASH, ONEMONEY, OMARI, ZIMSWITCH, …) | Collected by a rail, not yet received in the bank |
| `VENDOR_FLOAT` | Asset | Debit | Yes | vendor code (ECONET, NETONE, TELECEL, ESOLUTIONS) | Prepaid float held at airtime/bill vendors |
| `SUSPENSE` | Asset | Debit | Yes | reason code | Unidentified items awaiting investigation |
| `MERCHANT_PAYABLE` | Liability | Credit | **No** | merchant id | Owed to the merchant, not yet in a settlement |
| `SETTLEMENT_PAYABLE` | Liability | Credit | **No** | merchant id | Earmarked in a settlement, not yet paid out |
| `CUSTOMER_ORDER_OBLIGATION` | Liability | Credit | No | `ALL` | Consumer paid; airtime/bill not yet delivered |
| `CUSTOMER_REFUND_PAYABLE` | Liability | Credit | No | `ALL` | Refunds owed to customers |
| `TAX_PAYABLE` | Liability | Credit | Yes | tax code (VAT, IMTT) | Collected taxes owed to ZIMRA |
| `FEE_REVENUE` | Revenue | Credit | Yes | product line (MERCHANT_CHECKOUT, …) | PayPort's processing fees (net of VAT) |
| `COMMISSION_REVENUE` | Revenue | Credit | Yes | vendor code | Commission earned from billers/vendors |
| `PROVIDER_FEE_EXPENSE` | Expense | Debit | Yes | provider code | Rail charges |
| `LOSS_EXPENSE` | Expense | Debit | Yes | reason code | Approved write-offs |
| `FX_CLEARING` | Asset | Debit | Yes | currency pair | Two-leg conversions (used from Phase 7) |
| `FX_GAIN_LOSS` | Revenue | Credit | Yes | `ALL` | Realised FX differences (Phase 7) |
| `OPENING_BALANCE_EQUITY` | Equity | Credit | Yes | `ALL` | Balances brought forward at cut-over |

`May go negative = No` is enforced **by the database** at posting time.

## 5. Posting templates (versioned)

Every journal entry records `template_code` and `template_version`. Changing a template creates a new version; old entries keep pointing at the version that produced them.

| Template | Trigger (gateway event) | Lines (per currency) |
|---|---|---|
| `MERCHANT_COLLECTION` v1 | checkout session becomes PAID (live → LIVE book, test → TEST book) | Dr `PROVIDER_RECEIVABLE[rail]` gross · Cr `MERCHANT_PAYABLE[m]` net · Cr `FEE_REVENUE[MERCHANT_CHECKOUT]` fee − VAT · Cr `TAX_PAYABLE[VAT]` VAT (only when an approved VAT rate exists) |
| `MERCHANT_REFUND` v1 | gateway refund recorded | Dr `MERCHANT_PAYABLE[m]` amount · Cr `CUSTOMER_REFUND_PAYABLE` amount |
| `SETTLEMENT_CREATED` v1 | legacy settlement created | Dr `MERCHANT_PAYABLE[m]` net · Cr `SETTLEMENT_PAYABLE[m]` net |
| `SETTLEMENT_PAID` v1 | legacy settlement marked paid | Dr `SETTLEMENT_PAYABLE[m]` net · Cr `BANK_CASH[settlement account]` net |
| `CONSUMER_PAYMENT` v1 | consumer purchase payment confirmed | Dr `PROVIDER_RECEIVABLE[rail]` · Cr `CUSTOMER_ORDER_OBLIGATION` |
| `CONSUMER_FULFILMENT` v1 | consumer purchase completed | Dr `CUSTOMER_ORDER_OBLIGATION` face · Cr `VENDOR_FLOAT[vendor]` face − commission · Cr `COMMISSION_REVENUE[vendor]` commission |
| `CONSUMER_REFUND_DUE` v1 | consumer purchase moved to REFUND_PENDING | Dr `CUSTOMER_ORDER_OBLIGATION` · Cr `CUSTOMER_REFUND_PAYABLE` |
| `ADJUSTMENT` v1 | approved manual adjustment (four-eyes) | Any balanced lines |
| `REVERSAL` (built in) | approved reversal | Mirror of the original entry |

Fee amounts come from what the gateway already froze on the session (`commission_amount_cents`), so shadow figures reconcile with legacy figures by construction. VAT is split out only when finance has approved a VAT rate (§7).

## 6. Database-enforced posting (ADR-0004 accepted, ADR-0008)

All writes to journal tables go through `SQL SECURITY DEFINER` stored procedures. The application user has **EXECUTE** on them and **SELECT** on ledger tables, and nothing else on those tables.

`ledger_post(request JSON)` runs inside the caller's transaction and refuses to run with autocommit on. It:
1. Returns the existing entry when the idempotency key was already used with the same request hash, and fails with `IDEMPOTENCY_CONFLICT` when the key was used with a different request.
2. Validates the request:
   - at least two lines, each amount above zero, valid direction;
   - every account exists and is open, in the same entity and book;
   - debits equal credits **per currency**;
   - the accounting period is open.
3. Locks the affected balance rows **in account-id order**, which prevents deadlocks between postings.
4. Rejects the entry if any no-negative account would go below zero (`INSUFFICIENT_FUNDS`).
5. Inserts the entry and its lines, updates the balance projection, and stores a content hash.

Other procedures, all of which run as the definer:
- `ledger_reverse(...)`: builds the mirror entry. Each entry can be reversed once (unique `reverses_entry_id`), and a reversal cannot itself be reversed.
- `ledger_open_account(...)`: idempotent.
- `ledger_close_period(...)` / `ledger_reopen_period(...)`.
- `ledger_seal()`: appends a hash-chain seal over newly posted entries, so their content can be checked for tampering.
- `ledger_integrity_report()`: returns a list of every violation found.
  - unbalanced entries;
  - entries with fewer than two lines;
  - a balance row that does not equal the sum of its lines;
  - a no-negative account below zero;
  - a line whose currency differs from its account's;
  - a reversal that does not mirror its original;
  - a seal that no longer recomputes.

**Why procedures and not triggers.** Triggers need SUPER or `log_bin_trust_function_creators` when binlog is on, and that half-applied our earlier migrations (D2). An app user with ordinary `ALL` privileges could also drop them or `TRUNCATE` around them (R7). With procedures plus least-privilege grants, a bug in Java cannot write an unbalanced or overdrawn entry. The app cannot delete history either, because it has no DELETE, DROP or TRUNCATE on those tables.

## 7. Fees, taxes and approvals (slice 1.3)

- `fin_tax_rates` holds effective-dated rates: entity, tax code, currency, rate in basis points, cap, merchant-borne flag. A rate takes effect only with status `ACTIVE`, set by a second user. The database enforces that the approver differs from the requester.
- `platform_approvals` is a generic maker-checker store, used for tax rates, manual adjustments, reversals and period re-opening.
- **Nothing is seeded.** The current rates (VAT 15.5%; IMTT 2% USD / 1.5% ZiG, per reports of Finance Act No. 7 of 2025) must be entered and approved by finance after your tax adviser confirms how they apply to PayPort's fees.

## 8. Shadow posting (slice 1.2, ADR-0009)

- **Flag:** `payport.ledger.shadow.enabled`, default **false**.
- **Effect when on:**
  - Gateway status changes are posted to the ledger **after** the gateway's own transaction commits, on a separate thread.
  - Failures never reach the payment path; they are recorded in `ledger_shadow_failures` for review.
  - Idempotency keys are derived from the gateway record and event (for example `MERCHANT_COLLECTION:session:123`), so replays and duplicates post once.
- **Backfill:** a command posts history for a date range, so the ledger can start from the same point as the legacy figures.
- **Comparison report:** shows, per merchant and currency, the ledger `MERCHANT_PAYABLE` against the legacy unsettled net from `CheckoutSessionService.balanceByCurrency`, plus `SETTLEMENT_PAYABLE` against legacy settlements in PENDING or PROCESSING. Every difference is listed.

## 9. Test strategy (what runs here)

Everything financial is tested against **real MySQL 8.0.46** through JDBC (MariaDB Connector/J), on **real JUnit 5**.

| Suite | Content |
|---|---|
| Scenario suite (E1) | Every template, plus edge cases: rounding, multi-currency rejection, idempotent replay and conflict, closed period, reversal and double reversal, insufficient funds, test-book isolation, unknown or closed accounts, a single-line entry, a zero amount |
| Concurrency (E2) | 200 threads, 100 cents each, against a 10,000-cent balance |
| Atomicity (E3) | A connection killed mid-transaction; a rollback after a successful call |
| Privileges (E4) | As the restricted app user: INSERT, UPDATE, DELETE, TRUNCATE, ALTER and DROP on ledger tables all denied; EXECUTE allowed |
| Integrity detector | Each violation type is injected (as the DBA) and must be detected |

Spring wiring (beans, listener, properties) can only be verified in CI.
