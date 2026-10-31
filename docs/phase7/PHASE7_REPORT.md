# Phase 7: multi-country model and ISO 20022

_Branch `phase2-payment-lifecycle`, 2026-10-16._

**Scope (roadmap doc 08):**
- full country and entity model;
- a second, representative market configuration (no launch);
- canonical ↔ ISO 20022 mapping for the first verified message set (camt.053 and the sponsor bank's payout format);
- conformance tests.

**Acceptance:**
1. An ISO 20022 statement or payment flow is mapped, validated against the official schema, processed and traced through the ledger.
2. Adding a sandbox country needs configuration plus adapters only.

**Status:** see section 5. In short:
- Acceptance 2 is met.
- Acceptance 1 is built and tested end to end, but the conformance tests need the official schemas, and iso20022.org refused CI's download. They pass only once the schemas are on the classpath.

## 1. Country and entity model

**One file per market** (`src/main/resources/markets/<CC>.json`), loaded by `MarketRegistry`. It holds:
- status: `LIVE` or `SANDBOX`;
- the legal entity whose books it uses;
- timezone;
- regulators;
- mobile numbering plan;
- currencies, each with whether it may take live money, its symbol and its checkout product;
- the holiday calendar parameter;
- FIU report format and required screening lists;
- the sponsor bank: name, BIC, payout format, statement format, and an account per currency with its ledger owner.

Bank details can be overridden per deployment (`payport.markets.<CC>.bank.*`) without code changes.

**Invariants, checked when the application starts (it refuses to start if any fails):**
- every market's country, legal entity and currencies exist in the reference data;
- a **LIVE** market's country is enabled there;
- a **SANDBOX** market's country is not enabled, and none of its currencies may be marked live;
- a currency belongs to exactly one market;
- every currency has a bank account;
- the payout and statement formats have adapters.

**Code asks the registry instead of naming countries or currencies.** The places that hard-coded "USD or ZWG" now use it:
- checkout sessions;
- payment links;
- merchant enablements;
- the checkout product type and display symbol.

**Live money is refused in a sandbox currency twice:**
- enabling it for a merchant is refused;
- even an enablement written straight into the database cannot open live payments (`requireLiveCurrency` checks the market first).

| Market | Status | Entity | Currencies | Payout format | Statement format |
|---|---|---|---|---|---|
| ZW Zimbabwe | LIVE | `PAYPORT_ZW` | USD, ZWG (live) | `PAYPORT_CSV_V1` until the sponsor bank confirms its format | camt.053.001.08 (provisional) |
| ZM Zambia | SANDBOX: no licence, no launch | `PAYPORT_ZM_SANDBOX` | ZMW (never live) | pain.001.001.09 | camt.053.001.08 |

Zambia was chosen as the representative second market because it differs from Zimbabwe where it matters:
- a single local currency (no dual-currency rules);
- a different numbering plan (+260, MTN/Airtel/Zamtel prefixes);
- a different regulator (Bank of Zambia, FIC);
- a different bank.

Its holidays are deliberately left unsourced. Working-day deadlines there count weekends only until a calendar is configured.

## 2. ISO 20022 mappings

All three messages are validated against the **official ISO 20022 schemas**:
- Schemas are loaded from `iso20022/xsd/<message>.xsd`, which `scripts/iso20022/fetch-schemas.sh` downloads from iso20022.org. The script checks each file's target namespace and the SHA-256 pinned in `schemas.sha256`.
- Parsing refuses DTDs and external entities, and validation may not fetch anything.
- **Fail closed:** without the schema, nothing is produced or accepted.
- Every message produced or accepted is stored in `iso_messages` with its SHA-256.

### pain.001.001.09 (payout instructions) — `Pain001PayoutAdapter`

| Payport | ISO 20022 |
|---|---|
| Batch reference | `GrpHdr/MsgId`, `PmtInf/PmtInfId` |
| Item count, sum of net | `NbOfTxs`, `CtrlSum` (group and payment information) |
| Market bank account | `Dbtr`, `DbtrAcct/Id/Othr/Id`, `DbtrAgt` (BICFI, or the bank name) |
| Payout instruction reference | `PmtId/InstrId` **and** `EndToEndId`. The bank returns it in pain.002 and camt.053, which is how results and statements trace back. |
| Net amount (minor units) | `Amt/InstdAmt` (major units, `Ccy`) |
| Verified destination | `CdtrAgt` (institution, branch), `Cdtr/Nm` (account name), `CdtrAcct/Id/Othr/Id` (account; scheme `MSISDN` for mobile wallets) |

ISO payouts require a verified destination (`stl_items.destination_id`, new in V49).

### pain.002.001.10 (payment status report) — `Pain002Parser`, `BankMessageService`

| ISO 20022 | Payport |
|---|---|
| `OrgnlGrpInfAndSts/OrgnlMsgId` | Must be a batch Payport sent. Each reported instruction must belong to it. |
| `TxSts` ACSC + `AcctSvcrRef` | PAID with the bank's reference |
| `TxSts` RJCT + `StsRsnInf/Rsn/Cd` | FAILED, with reason, e.g. "AC04: Closed account number"; the sessions become eligible again |
| ACTC, ACCP, ACSP, PDNG… | Still in progress: no change |
| Group `GrpSts` RJCT | Every instruction FAILED |

Results go through the existing settlement rules:
- PAID only with the bank's reference;
- the file's hash is kept as proof;
- re-applying a report changes nothing.

### camt.053.001.08 (bank statement) — `Camt053Parser`, `BankMessageService`

**Checks before anything is stored:**
- the statement is for **the market's own account** in that currency;
- it **balances**: opening + booked credits − booked debits = closing (also a database CHECK);
- it has not been imported before (by file hash and by account and statement ID).

**How each entry is traced:**

| Entry | Trace |
|---|---|
| Booked debit with `EndToEndId` = a PAID payout | Payout → ledger journal **TRACED** (the journal is posted if missing) |
| Booked credit with the `EndToEndId` of a RETURNED payout | Return journal **TRACED** |
| Booked, but the payout isn't confirmed yet | NOT_YET_CONFIRMED + exception |
| Amount or currency differs | AMOUNT_MISMATCH + exception |
| Not traceable | UNIDENTIFIED + `MISSING_IN_PAYPORT` exception |
| Not booked (PDNG, INFO) | NOT_BOOKED. Not counted in the balance check. |

Nothing is adjusted automatically.

### Ledger

**Paid and returned payouts post to the ledger of the market's legal entity:**
- `STL_PAYOUT_PAID`: Dr MERCHANT_PAYABLE[merchant] · Cr BANK_CASH[market account].
- `STL_PAYOUT_RETURNED`: the reverse.

**How posting works:**
- Postings happen after the bank result commits. The ledger never rolls back a bank result (ADR-0009).
- Each has an idempotency key per payout and event.
- A posting the ledger refuses (for example, the merchant's collections are not in the ledger yet) is recorded and shows as LEDGER_PENDING on the statement trace.

## 3. Adding a sandbox country

What it takes (the ZM market was added exactly this way):
1. `markets/<CC>.json`.
2. One reference-data migration: three INSERTs for country, currency and legal entity. The application user may only read reference data (least privilege), so these rows come through Flyway like all other reference data. They are configuration, not code.
3. Adapters, only where the country's providers or bank use a format Payport doesn't have yet. ZM reuses the ISO 20022 adapters.
4. Optional parameters: `CALENDAR.<CC>.HOLIDAYS` and per-currency thresholds.

`MarketRegistryTest.noApplicationCodeNamesTheSandboxCountry` scans `src/main/java` and fails if any application code names the sandbox country or its currency or entity.

## 4. Tests

| Test | Proves |
|---|---|
| `MarketRegistryTest` (unit) | Both markets load; live and sandbox currency rules; numbering plans.<br>Deployment overrides work; the summary masks account numbers.<br>Invariants are refused when broken: a sandbox market with a live currency, an unknown payout format, a missing account.<br>**No application code names the sandbox country.** |
| `Iso20022MappingTest` (unit) | The official schemas are installed.<br>A camt.053 fixture validates and maps: balances, booked versus pending, references, counterparties.<br>pain.002 maps ACSC to PAID with the bank reference, RJCT to FAILED with the reason, ACSP to in progress; group rejection fails every instruction.<br>**Refused:** a schema-invalid document (missing `BkTxCd`), DTD/XXE, a message of the wrong type, amounts with more decimals than the currency allows. |
| `Iso20022ConformanceScenarioTest` (MySQL 8, whole application) | **The acceptance flow:**<br>1. Paid sessions are in the ledger.<br>2. A settlement batch is drafted, approved and instructed as **pain.001**, which validates against the schema (debtor account, BIC, CtrlSum, EndToEndIds, wallet by MSISDN); it is recorded, and re-instructing returns the same file.<br>3. A **pain.002** marks one payout PAID (with the bank reference) and rejects another (AC04); re-applying it is a no-op; an unknown original message is refused.<br>4. The ledger moves: merchant payable 0, bank −97.00, and the rejected payout is still owed.<br>5. A **camt.053** is validated, balanced and traced: entry → payout → `STL_PAYOUT_PAID` journal in `PAYPORT_ZW`. The unidentified credit becomes an exception; re-import is a no-op; the wrong account, an unbalanced statement and a non-conforming file are refused with nothing stored.<br>6. Ledger integrity is clean.<br>**Sandbox:** ZM live enablement is refused, even when the enablement is forced in the database; a ZMW camt.053 is processed into the sandbox entity's records. |

## 5. Results and what is open

**CI run on commit 5210e5f (2026-10-16)**
- `build-and-test`: 183 passed. The 7 ISO 20022 tests fail with "schema not installed".
- `ledger-db`: 106 / 106.
- `migrations-production-like` (through V49): pass.
- Every other Phase 7 test passes, including `MarketRegistryTest` and the market checks inside the scenario.

**Why the schemas are missing:** iso20022.org answers GitHub's runners with **HTTP 403 "SWIFT site off-line"**. It blocks the CI network, not the request itself.

**Structural check done meanwhile (not a substitute for the official schemas).** The `pyiso20022` package (PyPI) contains bindings generated from these exact message versions. The following were checked against them for element names, order, required elements, code lists and patterns:
- the camt.053 and pain.002 fixtures;
- the group-rejection pain.002;
- a pain.001 in exactly the adapter's output shape.

All conform. The two negative cases the tests rely on are rejected: a missing `BkTxCd`, and `DEBIT` instead of `DBIT`.

**Open**
- **The official schemas.** iso20022.org refused the CI runner's download (HTTP error; the URLs are the official ones: camt.053.001.08 = message 12736, pain.001.001.09 = 14346, pain.002.001.10 = 14396). Until they are installed, the three ISO 20022 tests fail with "schema not installed": deliberately, not skipped.
  - To install them, download the three XSDs from iso20022.org (message archive) on a normal machine. Then either commit them to `src/main/resources/iso20022/xsd/`, or let CI fetch them if the site allows it.
  - Record their SHA-256 in `scripts/iso20022/schemas.sha256`.
- **The sponsor bank's actual payout and statement formats.** ZW stays on the interim CSV for payouts. camt.053 for ZW is marked provisional. If the bank uses ISO 20022 with its own usage guidelines (market practice, mandatory fields), those rules go into the adapters as an additional validation layer on top of the base schema.
- **Inbound collections from providers on the bank statement** (credits) are currently UNIDENTIFIED exceptions. Matching them to `PROVIDER_RECEIVABLE` settlements is the next step once the provider settlement references are known.
