# Phase 0: Provider and biller integration inventory

**Status levels**, as required in §7 of your prompt:
1. Interface implemented
2. Simulator tested
3. Provider sandbox tested
4. Real provider test environment verified
5. Production approval obtained
6. Production operation verified

Levels 5–6 come from **your** report that the gateway runs in production. I have not observed it [User]. Nothing has reached level 2 or above in this assessment: there are no simulators for the gateway adaptors and no sandbox access.

| Provider / biller | Capability | Code | Highest level, evidence | Gaps |
|---|---|---|---|---|
| EcoCash | Collect (C2B), status query, callback | `EcoCashAdaptor` | 6 [User] | Callback unauthenticated in G; reconciler gives up to FAILED; no refund/reversal API use; no simulator |
| OneMoney | Collect, status, callback | `OneMoneyAdaptor` | 6 [User] | Callback amount parsed as 0; no simulator |
| O'mari | Collect (OTP confirm) | `OmariPaymentAdaptor` | 6 [User] | No callback verification review yet; no simulator |
| ZimSwitch | Card / hosted widget collect, status query | `ZimSwitchAdaptor`, `ZimSwitchCallbackController` | 6 [User] | Confirms by query (good). Refunds not implemented |
| Econet airtime/data | Fulfilment (XML-RPC) | `EconetAirtimeAdaptor` | 6 [User] | In-memory fulfilment queue |
| NetOne airtime/data | Fulfilment | `NetOneAirtimeAdaptor` | 6 [User] | One unit test exists (message format) |
| Telecel airtime | Fulfilment | `TelecelAirtimeAdaptor` | 6 [User] | — |
| e-Solutions: ZESA tokens | Biller fulfilment | `ZesaTokenAdaptor` | 6 [User] | Bespoke per biller |
| e-Solutions: Bulawayo City Council, ZOL, Nyaradzo, Moonlight | Biller fulfilment | individual adaptors | 6 [User], assumed for all | Bespoke per biller; no shared catalogue/validation framework |
| Telecash | Collect | none | 0 | Specification and credentials needed |
| InnBucks | Collect | statement parser only | 0 (recon only) | Specification and credentials needed |
| Africa's Talking | SMS | `SmsNotificationService` | 6 [User] | — |
| Africa's Talking | USSD | branch `UssdService` | 1 | Short code, aggregator contract |
| WhatsApp Cloud API | Messaging | branch | 1 | Meta business verification |
| Bank payouts / ZIPIT / ZIPSS | Payout | branch `PayoutRail` + bank-file CSV | 1 (CSV format is invented, not a bank's spec) | Sponsor bank, its file or API spec |
| Trust bank statements | Reconciliation | branch CSV import | 1 | Bank's MT940 / CAMT.053 / CSV spec |
| National ID, credit bureau | KYC/KYB | branch manual + sandbox | 1 (stub) | Access agreements |
| RBZ FX rates | Rates | branch manual entry | 1 | Feed access |
| National Switch QR scheme | QR acceptance | branch `EmvQr` (non-conforming) | 0 for the conforming design | Scheme participation, MAI ID |
| Card acquiring (international) | Cards | none | 0 | Acquiring partner; PCI scope decision |
| PAPSS / regional rails | Cross-border | none | 0 | Participation is via banks; RBZ began PAPSS implementation in 2023 |

## Target adapter contract (Phase 5, summarised)

Each adapter declares its **capabilities** (`INITIATE`, `STATUS`, `CALLBACK_VERIFY`, `REFUND`, `REVERSAL`, `PAYOUT`, `PAYOUT_STATUS`, `REPORT_FETCH`, `HEALTH`). For each capability it also declares conditions: currencies, countries, amount bands and idempotency semantics. Every operation returns a classified outcome:

| Outcome | Meaning |
|---|---|
| `ACCEPTED` | Request taken |
| `SUCCEEDED` | Definite success |
| `DECLINED` | Definite, retryable with a new attempt |
| `FAILED_DEFINITE` | Definite failure |
| `UNKNOWN` | Never retried blindly; resolved by status enquiry |
| `TECHNICAL_RETRYABLE` | Request provably not received |

Each adapter has:
- a **simulator** implementing the same contract;
- a **contract test suite** run against both the simulator and, when credentials exist, the provider sandbox.

Biller-specific data (catalogue, validation rules, reference format, receipt fields, reconciliation file mapping) becomes **configuration** plus a small adapter, not a new payment engine.
