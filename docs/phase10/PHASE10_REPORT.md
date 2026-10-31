# Phase 10: verification, integration and operational readiness

_Branch `phase2-payment-lifecycle`, 2026-10-22. Owner instruction: a formal verification phase, not new
features; preserve the gateway; no production deployment, live payouts, settlement automation or
irreversible financial changes without the owner's approval._

**Verdict.** The automated evidence is green (§11). Integration acceptance is **not started**: no provider
sandbox or certification has been run. Production readiness is **not met**: the penetration test, staging
load test, provider sandbox tests and a restore drill on a real backup are still outstanding, and so are the
conditions in §10. This report does not describe the platform as production-ready.

Three kinds of result are kept apart throughout:

| Level | Meaning | Status |
|---|---|---|
| **A. Automated tests** | CI on the exact commits listed in §1, with real MySQL 8, Flyway and the ledger procedures, and stubs or fixtures for providers | **Pass** (§1, §11) |
| **B. Integration acceptance** | Each provider's sandbox or UAT, run with real credentials; certification where the provider requires it | **Not started**: 0 of 14 external adapters (§5) |
| **C. Production readiness** | B, plus an external penetration test, a staging load test, a restore drill on a real backup, the §10 conditions, and owner sign-off | **Not met** (§9) |

## 1. Baseline: exact commits and CI runs

| Repository | PR / branch | Commit tested | CI run | Result |
|---|---|---|---|---|
| payport (backend) | `phase2-payment-lifecycle` (Phase 8/9 baseline) | `29f628e048a414e6dd7040d8823c501a5671dca9` | [39791437666](https://github.com/bdube1/payport/actions/runs/39791437666) | success. `a791e8d` that followed changed docs only, so the result applies to it |
| payport (backend) | `phase2-payment-lifecycle` (Phase 10 code) | `020695747fdb9ed2226bfd3b10d67b6993eac410` | [39801097989](https://github.com/bdube1/payport/actions/runs/39801097989) | success, all four jobs. The earlier run [39798345512](https://github.com/bdube1/payport/actions/runs/39798345512) on `1a8f3bd` failed and found D10-6 (§3). Commits after `0206957` change documentation only |
| payport-consumer | [PR #1](https://github.com/bdube1/payport-consumer/pull/1) `payment-flow-outcomes` | `6fed635c903b943d4be6d5d5388609b1fded8ee0` | [38948708902](https://github.com/bdube1/payport-consumer/actions/runs/38948708902) | success |
| payport-mobile | [PR #1](https://github.com/bdube1/payport-mobile/pull/1) `payment-flow-and-push` | `3bbb2e02de54c76b5513f6fb513616aef61deff4` | [38949084460](https://github.com/bdube1/payport-mobile/actions/runs/38949084460) | success |
| payport-ops | [PR #1](https://github.com/bdube1/payport-ops/pull/1) `platform-compliance-console` | `b9a3ae2a52a81f4b341f912e087d417786b431ec` | [38949527528](https://github.com/bdube1/payport-ops/actions/runs/38949527528) | success |
| payport-merchant | [PR #1](https://github.com/bdube1/payport-merchant/pull/1) `merchant-signup` | `4611209bcef1a782c91bf54b84f1d09fd196d724` | none: the branch has no CI workflow | Its head is an ancestor of PR #2, so the PR #2 run covers its code. It was also built and contract-checked locally |
| payport-merchant | [PR #2](https://github.com/bdube1/payport-merchant/pull/2) `merchant-dashboard-platform` | `8c6361f5e50ed7f7d039dddbd560cc135a3eefd3` | [38949253490](https://github.com/bdube1/payport-merchant/actions/runs/38949253490) | success |

Checked on 2026-10-22 through the GitHub REST API: each frontend run's `head_sha` equals the PR's current
head, so the results apply to the final pushed commits. No frontend changed in Phase 10. The only API change
is one added read-only route (§6), which no frontend calls. The frontends' contract checks only require that
every route they call exists, so they remain valid.

## 2. R76: the XML-RPC library (Econet airtime)

| Field | Finding |
|---|---|
| Component | `org.apache.xmlrpc:xmlrpc-client` / `xmlrpc-common` 3.1.3. Used only by `EconetAirtimeAdaptor` (Econet airtime and data delivery). The Apache project is end-of-life and has no fixed release |
| Vulnerability 1 | **CVE-2019-17570**, CVSS 3.1 **9.8** (critical, AV:N/AC:L/PR:N/UI:N/C:H/I:H/A:H). `XmlRpcResponseParser` passes a server-supplied `faultCause` to `ObjectInputStream`, so whoever answers the client can run code in Payport through a gadget chain on the classpath |
| Vulnerability 2 | **CVE-2016-5004 / GHSA-r2pg-w96p-pcpj**, CVSS 3.0 **6.5** (moderate, availability only). Decompression denial of service through Content-Encoding |
| Scanner coverage | OSV reports only CVE-2016-5004. CVE-2019-17570 is filed under the `org.apache.xmlrpc:xmlrpc` artifact, so CI's "1 advisory" understated the exposure (new risk R82). The Phase 9 report wrongly called the GHSA a deserialisation flaw; corrected |
| Exposure | Every Econet delivery. The endpoint is configuration (`ECONET_HOST`, port and path), and the transport is **plain HTTP** |
| Exploitability | An attacker needs to answer the client: a compromised Econet server, anyone on the network path, or a mis-set host. The private network is **not** treated as sufficient evidence |
| Mitigation in Phase 10 | `GuardedXmlRpcTransportFactory`: every response is read through `XmlRpcResponseGuard` before the library parses it. The guard caps the read at 256 KiB, which also bounds a decompression bomb. It refuses `faultCause`, `serializable` values, serialised-Java stream markers (raw and base64), DOCTYPE and ENTITY. The client config turns off extensions, exceptions, gzip requesting and gzip compressing |
| Evidence | `Phase10UnitTest`: genuine responses and faults pass; each attack shape is refused; a 64 MiB gzip bomb stops reading near the cap |
| Residual risk | A parser flaw that the screen does not cover, and denial of service from a hostile endpoint, which is bounded but possible |
| Conditions for production Econet traffic | (1) TLS or an IPsec/VPN tunnel to Econet with the server identity pinned; (2) replace the library with a minimal XML-RPC client that has no Java serialisation, and retire this risk |
| Owner / review | Engineering lead. The residual-risk acceptance belongs to the Payport owner and is **not yet signed**. Review by 2027-01-22, or before Econet go-live if earlier |

## 3. Financial integrity: concurrency, idempotency, freeze, rollback, recovery

`FinancialIntegrityScenarioTest` runs on MySQL 8 with the real ledger procedures. After every scenario it
checks the books:
- each wallet's ledger balance equals the sum of its recorded operations;
- no `CUSTOMER_WALLET` or `AGENT_FLOAT` account is below zero;
- every journal balances per currency;
- `ledger_integrity_report()` is empty.

The only failures accepted from racing threads are business refusals (insufficient funds, frozen, already
used). A deadlock, a pool timeout or an SQL error fails the test.

| Scenario | Expected and asserted |
|---|---|
| 10 transfers and 10 FX conversions of 1.00 race for a 10.00 balance | Exactly 10 succeed; the balance ends at 0 and never goes negative |
| 30 opposing transfers A↔B at once | Money is conserved and there is no deadlock (locks are taken in wallet-id order) |
| One transfer retried 10× at once with the same `clientRef` | One debit, one credit, one operation |
| One top-up delivered 10× concurrently | One credit; every caller gets the same ledger entry |
| A cash-out code redeemed by 10 agent threads | Paid out once. The float and the wallet are exact, including the commission |
| 20 cash-ins race for an agent float of 1,000.00 | Exactly 10 succeed; the float ends at 5.00 and never goes negative |
| The same wallet refund raced 10× | One credit, one REFUND operation, the payment REFUNDED once |
| The same FX quote executed 10× | One conversion |
| Freeze, then 10 transfers; then a freeze racing 20 transfers | A frozen wallet sends nothing. Under the race, what committed before the freeze stands, nothing after it, and the books match |
| Fault injected **after** the journal was posted, in the same transaction | The journal and the operations roll back together. The retried request then succeeds once |
| Top-up whose delivery outcome was lost (crash after crediting, and crash before crediting) | The status query resolves the first as DELIVERED with no second credit. For the second, NOT_FOUND, then an ops retry, then two workers: credited exactly once |

Changes made for this item:
- **D10-2 (fixed):** a wallet refund checked the payment status and then credited the wallet in separate
  steps, so two finance users refunding at once relied only on the ledger key. The refund now locks the
  payment row, and the credit, the journal and the REFUNDED status commit in one transaction.
- **D10-3 (fixed, preventive):** FX conversion, cash-in, cash-out and float approval could open ledger
  accounts on a second pooled connection while holding row locks. That is the pool-starvation pattern found
  in Phase 9. The accounts are now opened before any lock is taken.
- **D10-6 (found by this suite, fixed):** under 30 concurrent operations the connection pool ran dry
  (`HikariPool … total=20, active=20, waiting`). The wallet's maximum-balance check read the ledger
  balance on a **second** pooled connection while the operation held its wallet row locks on the first.
  The Phase 9 fix moved the journal posting onto the operation's connection but missed this read. In the
  same CI run the Phase 9 test `walletUnderConcurrentSpends_…` also failed once (9 of 10 fundable transfers
  succeeded). That test had passed before, so the defect was latent and intermittent. `LedgerClient.balance`
  now reads on the caller's transaction connection when there is one (run 39798345512 failed; fixed in
  `0206957`).

Earlier evidence still applies: `SecurityResilienceScenarioTest` (30 transfers against money for 10; one
payment confirmed 10×), `LedgerConcurrencyTest` (200 parallel debits, same-key posts, opposing transfers).

## 4. Payment lifecycle

No lifecycle code changed in Phase 10. The required behaviours are covered by existing scenario tests that
ran green in every run in §1:

| Requirement | Test (class → method) |
|---|---|
| Provider timeout, outcome unknown | `PaymentLifecycleScenarioTest` → `readTimeout_thenLateSuccess_byResolverQuery`, `readTimeout_thenLateSuccess_byCallback`, `serverError_isUnknown_thenDefiniteFailureByQuery`, `retryAfterUnknownOutcome_isRefused`, `noNewAttemptWhileTheOutcomeIsUnknownOrTimedOut` |
| Delayed, duplicate and out-of-order callbacks | `duplicateCallbacks_sequentialAndConcurrent_haveOneEffect`, `callbackBeforeSubmitResponse_*`, `outOfOrder_*`, `queryVerifiedProvider_*`, `badSignature_changesNothing` |
| Reversal after success | `outOfOrder_failureAfterSuccess_isAConflictAndNeverUndoesTheConfirmation` (raised as a conflict, never applied silently) |
| **Late success after the payment window: never fulfilled** | `windowClose_toldTimedOut_thenLatePaymentIsNeverFulfilled_onlyRefunded`, `ussdWaitEnds_customerToldTimedOut_paymentClosedNotFailed_andLateSuccessIsNeverFulfilled`. The financial exception PAID_AFTER_TIMEOUT is raised separately and can only be resolved as REFUNDED |
| Amount mismatch | `amountMismatchInCallback_isAConflict`, `resolverAmountMismatch_goesToManualAndIsNotApplied` |
| Delivery timeouts and recovery | `FulfilmentJobScenarioTest` (10 scenarios: never resent on an unknown outcome; resolved by query; exactly once under concurrent workers) and §3 above |
| Reconciliation | `ReconSettlementScenarioTest.injectedDifferencesAreDetectedCategorisedAndNothingIsAdjusted` |

The payment-window values were not changed.

## 5. Provider adapters: what has and has not been proven

**No adapter has passed a provider sandbox or UAT test.** No credentials or sandbox access are available to
this build, and the build network blocks provider hosts. Each register entry stays `SPEC_ONLY`;
`CircuitBreakerTest` fails the build if any entry claims sandbox verification without the evidence workflow.

| Provider | Kind | Evidence level | Automated evidence | Needed for acceptance |
|---|---|---|---|---|
| EcoCash | payment | Spec fixtures | `EcoCashContractTest` (local HTTP stub of the spec) | Sandbox credentials and run; callback secret |
| OneMoney | payment | Mocked | Lifecycle tests through the orchestrator; no wire fixture | Spec fixture, sandbox, TLS endpoint (R80) |
| O'mari | payment | Mocked | Lifecycle tests (OTP flow) | Sandbox (base URL currently points at UAT) |
| ZimSwitch (cards) | payment | Unit (result codes) | `ZimSwitchResultCodeTest` | Sandbox, webhook secret, certification |
| MTN MoMo | payment | **Spec fixtures (new)** | `Phase10UnitTest`: token, RequestToPay headers and body, status query, error mapping | Sandbox subscription key, then MTN go-live approval |
| Airtel Money | payment | **Spec fixtures (new)** | `Phase10UnitTest`: token, collection request, status mapping | Sandbox client credentials, then Airtel approval |
| Econet airtime and data | delivery | Mocked; hardened (R76) | `Phase10UnitTest` (guard) | TLS/VPN, sandbox run, library replacement |
| NetOne airtime and data | delivery | Unit (messages) | `NetOneAirtimeAdaptorMessageTest` | Sandbox |
| Telecel airtime | delivery | Mocked | none at the wire | Spec fixture, sandbox |
| e-Solutions billers (ZESA, Nyaradzo, Moonlight, ZOL, Bulawayo) | delivery | Spec fixtures | `EsolutionsContractTest` (stub) | Test environment run per biller |
| Card acquiring (POS) | — | **Not built** | Card requests fail closed (`Phase8CompletionScenarioTest`) | An actual acquirer integration and certification |
| Merchant checkout, wallet (internal) | internal | In-process | Scenario tests | — |

## 6. New modules end to end

| Module | End-to-end evidence | Stays disabled until |
|---|---|---|
| Wallets | `Phase8CompletionScenarioTest` over HTTP (top-up, transfer, pay, refund, convert) and §3 | Licence answer recorded by two compliance officers; line switched on; `WALLET` method and `WALLET_TOPUP` product activated; tier limits confirmed |
| Agents | Phase 8 test (apply, four-eyes float, cash-in, single-use cash-out) and §3 | Licence answer; each agent approved by compliance; commission confirmed |
| POS | Phase 8 test (signed requests, replay refused, sale as checkout) | Licence answer; **cards stay unavailable** without an acquirer integration |
| WhatsApp | Phase 8 test (signed webhook, menus, de-duplication) | Licence answer; Meta app secret, verify token, sender |
| USSD | `PlatformControlsScenarioTest.ussdMenus_signed_channelSwitch_andStatusOnlyForThePayer` | Aggregator secret |
| QR | `PlatformControlsScenarioTest` (EMVCo payload, resolve) | Licence answer; national QR scheme details |
| FX | Quotes and conversions (Phase 8 and §3) | **Execution is off**: needs the FX_CONVERSION line on (licence answer) **and** an ACTIVE rate approved by a second finance user (database CHECK). With no approved rate, nothing executes |

`OperationalReadinessScenarioTest` checks a fresh database:
- no product line is enabled;
- no licence-gated channel is enabled;
- the WALLET method and WALLET_TOPUP product are inactive;
- no FX rate is active;
- FX quotes and wallet opening return 503, and an inactive product cannot be bought;
- settlement is in PARALLEL mode, with automation off and reconciliation required.

Phase 10 added one route: `GET /api/v1/admin/provider-settlement-rules/positions`. It is read-only, and only
ADMIN, FINANCE and COMPLIANCE can call it.

## 7. Settlement and bank-statement matching

Settlement stays in **draft / approval / comparison** mode:
- `payport.settlement.mode=PARALLEL`, and automation is off by default;
- PARALLEL batches are never instructed (`ReconSettlementScenarioTest.parallelModeBatchesAreNeverInstructed`);
- no batch was instructed in any Phase 10 test;
- live payout automation is **not** enabled, and the existing settlement process is **not** retired.

`OperationalReadinessScenarioTest` runs representative camt.053 fixtures against a four-eyes EcoCash rule
after 1,000.00 of collections:

| Case | Result |
|---|---|
| Partial settlement (400.00 of 1,000.00) | Booked. The positions view shows 600.00 OUTSTANDING |
| The same bank line on the intraday and the end-of-day statement | **Booked once**; the second sighting is traced "already booked from an earlier statement" |
| Exact re-import of a file | Recognised as already imported; nothing new |
| Another file reusing a statement id | Refused |
| Reversal (a debit from the provider) | Not booked. UNIDENTIFIED plus a reconciliation exception |
| Unknown credit | UNIDENTIFIED plus an exception |
| Pending (PDNG) line | NOT_BOOKED |
| Over-settlement | Shown as OVER_SETTLED (−50.00) for investigation |
| Same bank reference later shown with a different amount | Not booked. LEDGER_PENDING plus an exception |
| Settlement batches | None instructed; the ledger integrity report is empty |

**D10-4 (fixed):** provider settlements were keyed by statement id and line number, so a bank line that
appears on both an intraday and an end-of-day statement would have been booked twice. The key is now the
bank's own entry reference, with the old key used only when the bank gives none.

## 8. Access control and configuration review

| Area | Evidence |
|---|---|
| Admin and merchant routes | `SecurityResilienceScenarioTest.authorizationMatrix_*`, built from the live route table, so new routes are covered |
| Customer isolation | Another customer cannot read a wallet statement (400 "No such wallet"), pay someone else's payment from their own wallet, or see the payer's phone on a status lookup |
| Merchant isolation | Merchant B cannot disable A's terminal (the terminal stays ACTIVE), cannot list it, and cannot read A's checkout session by id or by reference (404) |
| API keys | A checkout-scoped key is refused on POS, agent and API-key management routes (403) |
| Refunds | Customers, merchants, OPS and COMPLIANCE are refused wallet refunds; customers, merchants and COMPLIANCE are refused payment refunds. Nothing changes until FINANCE refunds, once. The operator comes from the login |
| Exports | CSV export is refused to customers and merchants and allowed to staff (it contains customer phones; staff access is audited) |
| Webhooks and replay | POS (HMAC with a nonce and a clock window), WhatsApp (app-secret signature), USSD (HMAC), and merchant webhooks (signed, SSRF-blocked) are covered by the Phase 8 and merchant tests. Provider callbacks change nothing until a status query confirms them |
| Secrets | No secret has a default. `JWT_SECRET` and `DB_PASSWORD` must be set. `SecretBox` refuses to start in production without a key, or with a zero-entropy key. Actuator exposes none of env, beans, heapdump, threaddump, configprops, loggers or shutdown (`Phase10UnitTest.productionDefaults_failSafe`) |
| Production configuration | Open findings: R79 (`ddl-auto=update`), R80 (plain-HTTP provider defaults), R81 (transaction token not enforced yet). Owners and dates are in the risk register |

## 9. Outstanding external activities (tracked until completed)

| Activity | Status | Owner | Acceptance criterion |
|---|---|---|---|
| External penetration test (scope in `docs/phase9/PEN_TEST_SCOPE.md`) | **Not started** | Payport owner commissions; security owner | Report received; no open critical or high findings; retest done |
| Staging load test (`scripts/phase9/k6-staging.js`) | **Not started**: no staging environment yet | Engineering lead | All SLO thresholds met at the target arrival rates on production-like infrastructure |
| Provider sandbox tests (14 external adapters, §5) | **Not started**: no credentials | Engineering lead with each provider | Evidence recorded per flow (`POST /api/v1/admin/integrations/{code}/verifications`) and register raised to SANDBOX_VERIFIED in a reviewed change |
| Restore drill on a real backup (`scripts/phase0.5/restore-drill.sh`) | **Not started**: CI drills use test data | Operations / DBA | Restored from a production backup on a non-production server; checks pass; RTO ≤ 60 min, RPO ≤ 5 min |
| R76 conditions (TLS/VPN to Econet, library replacement) | Open | Engineering lead | Both done before Econet production traffic |
| Monitoring (`PAYPORT_METRICS_TOKEN`, alert rules) | Open | Operations | Alerts fire in a staging test |

## 10. Defects and risks found in Phase 10

| ID | Finding | Severity | Status | Owner |
|---|---|---|---|---|
| D10-1 / R79 | `spring.jpa.hibernate.ddl-auto=update` in production config | High | Open: change to `validate` once the owner approves; `validate` proven to boot | Engineering lead |
| D10-2 | Wallet refund not serialised with the payment status | High | **Fixed and tested** | — |
| D10-3 | Ledger accounts opened while holding locks (pool starvation) in FX, cash-in, cash-out, float approval | Medium | **Fixed** | — |
| D10-6 | Ledger balance read on a second pooled connection under wallet locks: pool exhaustion, an intermittent refusal of a fundable transfer | High | **Fixed and tested** | — |
| D10-4 | Provider settlement could double-book a bank line seen on two statements | High | **Fixed and tested** | — |
| D10-5 / R76 | Critical XML-RPC deserialisation (CVE-2019-17570) under-reported; earlier docs wrong | Critical (mitigated) | Mitigated; production conditions in §2 | Engineering lead; owner acceptance pending |
| R80 | Plain-HTTP provider endpoints (OneMoney default, Econet) | High | Open | Engineering lead and network provider |
| R81 | Transaction access token not enforced yet | Medium | Open by design until the frontends send it | Engineering lead |
| R82 | Dependency scanner blind to artifact-renamed advisories | Medium | Open | Engineering lead |

## 11. Phase 10 automated results

Backend run [39801097989](https://github.com/bdube1/payport/actions/runs/39801097989) on `020695747fdb9ed2226bfd3b10d67b6993eac410`:

| Job | Result |
|---|---|
| build-and-test | **341 tests: 235 passed, 0 failed**. The 106 skipped are the database-only tests that run in `ledger-db`. This includes the 21 new Phase 10 tests: `FinancialIntegrityScenarioTest` 11, `OperationalReadinessScenarioTest` 3, `Phase10UnitTest` 7 |
| ledger-db | **106 / 106**. DR drill: **PASS** (backup → restore → identical rows, procedures, balances, integrity; restore 2 s) |
| migrations-production-like (V1–V52, binlog, non-SUPER user) | pass |
| dependency-scan | 1 advisory listed (CVE-2016-5004). CVE-2019-17570 is known but not reported by the scanner (R76, R82) |
| Latency on the CI runner (400 wallet reads, 8 clients) | p50 39 ms, p95 64 ms, p99 118 ms. This is a runner measurement, **not** a load test |

The failed run before the fix, 39798345512, is kept as evidence: 4 failures. One was the test's own
arithmetic. Three were D10-6, including one in the Phase 9 concurrency test.

## 12. Acceptance criteria for this phase

| # | Criterion | Met? |
|---|---|---|
| 1 | Status records exact SHAs, PRs and CI runs, and results apply to the final commits | Yes (§1) |
| 2 | R76 reviewed with identifier, severity, exploitability, exposure, mitigations, owner and review date | Yes (§2). Owner sign-off of the residual risk is pending |
| 3 | Financial operations balanced, atomic, duplicate-safe and overspend-safe under concurrency | Yes (§3, automated) |
| 4 | Lifecycle including late success never fulfilled; exception resolved separately | Yes (§4, automated) |
| 5 | Adapter evidence stated honestly | Yes (§5): none sandbox-verified |
| 6 | New modules verified; card and FX execution stay unavailable without prerequisites | Yes (§6) |
| 7 | Settlement fixtures pass; settlement in draft/approval/comparison mode | Yes (§7) |
| 8 | Access-control review with negative tests | Yes (§8). Three configuration findings open |
| 9 | External activities tracked as outstanding | Yes (§9) |

## 13. Recommended next phase

**Phase 11: staging integration and acceptance** (no production change):
1. Stand up a production-like staging environment with TLS or a VPN to providers. Set `ddl-auto=validate`
   (R79) and explicit provider endpoints (R80).
2. Provider sandbox runs in priority order: EcoCash, OneMoney, Econet, e-Solutions, then the others. Record
   the evidence and raise the register entry by entry.
3. Run the k6 load test and a restore drill from a real backup there. Commission the penetration test
   against staging.
4. Replace the XML-RPC library (R76) and add a second dependency scanner (R82).
5. Start the settlement parallel run against the existing process and compare daily. Retire nothing and
   automate nothing without the owner's approval.

Then a go-live decision by the owner, line by line.
