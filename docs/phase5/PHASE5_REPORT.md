# Phase 5: provider and biller integrations

_Branch `phase2-payment-lifecycle`, 2026-10-16. Scope from roadmap doc 08:_
- an adapter contract with capability declarations;
- hardening of the existing adapters;
- contract tests;
- health checks and circuit breakers;
- a biller framework demonstrated on ZESA and one more biller.

**Roadmap acceptance:** "one provider and one biller flow end to end against real test environments, with status recorded in the integration register."

**Status: not met. Everything up to it is built and tested.** The owner has no provider sandboxes, ZimSwitch webhook secret or sponsor-bank payout format. So every adapter is tested against fixtures built from its specification, and that is the evidence level the register records: `SPEC_ONLY`. The register has an evidence workflow ready for the day a sandbox run happens (section 4).

## 1. What was built

| Area | What it does | Where |
|---|---|---|
| **Integration register** | One entry per adapter with:<ul><li>kind (payment, fulfilment, biller, internal);</li><li>currencies;</li><li>declared operations (submit, callback, status query, refund, lookup, deliver, resend lookup…);</li><li>how callbacks are authenticated;</li><li>the configured endpoint, from which the **environment is detected**: TEST, PRODUCTION, INSECURE_HTTP or NOT_CONFIGURED;</li><li>the verification level.</li></ul>A test fails if any loaded adapter has no entry, or any entry has no adapter. Payments in a currency the provider does not declare are refused before sending. | `integrations/register.json`, `IntegrationRegister` |
| **Kill switch** | Operations can suspend a provider with a reason. It is stored in the database, so every instance obeys it within 5 seconds and it survives restarts. Resuming is a separate audited action. | `ProviderHealthService`, `V43` |
| **Circuit breakers** | One per provider. 5 consecutive *transport* failures (timeouts, refused connections, 5xx, unknown outcomes) open the breaker for 60 seconds; then one trial request decides whether it closes. A provider that answers "declined" counts as healthy. | `CircuitBreaker` |
| **Health** | Per provider: status (UP, DOWN, RECOVERING, SUSPENDED), circuit state, success, failure and refusal counts, last failure, p50/p95 latency. | `GET /api/v1/admin/integrations` |
| **Biller framework** | A biller declares its name, account label and format, a hint for customers, currencies and amount limits. It can look an account up (holder, accepted currencies, payable or not, and why). Account, amount and currency rules are enforced at **`/initiate`, before the customer pays**. Before this, they were only discovered at delivery, after the money was taken. ZESA and Nyaradzo are fully specified; ZOL, Bulawayo and Moonlight are on conservative rules until their specs are confirmed. | `billers/*`, `GET /api/v1/billers`, `POST /api/v1/billers/{code}/lookup` |
| **Contract tests** | See section 3. | |

**The key property of the kill switch and breakers: they refuse before anything is sent.** A refusal can therefore never create an unknown outcome.
- A refused payment stays as it was and can be paid later. The customer gets **503 `PROVIDER_UNAVAILABLE`**: "…No money was taken. Please try again later or use another option."
- A refused delivery is a definite "not sent" and is retried later.

## 2. Hardening: defects found and fixed

| Risk | Defect | Fix |
|---|---|---|
| **R57 (money)** | For every eSolutions biller (ZESA, ZOL, Bulawayo, Nyaradzo, Moonlight), the vendor reference sent to eSolutions was never stored. When a vend's outcome was unknown, the follow-up lookup sent the **Payport** reference, which eSolutions had never seen. **A ZESA token that had been issued (drawing Payport's float) could be recorded as not delivered and refunded.** Separately, lookup references were the time to the second, so two lookups in the same second collided. | A delivery reference per attempt is derived from the payment reference and **recorded before sending**, in the same transaction that marks the delivery as started. The adapters send it, and the later lookup uses it. Lookup references are unique (counter-based). Proven end to end: unknown outcome → looked up by the exact reference → completed, **exactly one vend**. |
| R62 | EcoCash callbacks were always recorded as USD. Amounts were converted through floating point. A synchronous EcoCash decline (HTTP 200 with FAILED) was treated as "sent", so the customer waited out the whole payment window and was told TIMED_OUT instead of EcoCash's reason. | The callback's own currency is used; amounts are exact decimals; an immediate decline is definite and passes on EcoCash's words. |
| — | ZESA lookups after an unknown outcome were always made in USD. | They use the payment's currency (ZWG meters). |
| — | ZESA customer information (name, address, raw response) was logged at INFO. | Moved to DEBUG; the meter number is masked. |
| R58 | The frontends read `response.data.message`; the API only sent `error.message`. | Error responses also carry a top-level `message` (see `docs/frontend/FRONTEND_INTEGRATION_PLAN.md`). |
| R60 | Public bill lookups return holder names for any account number. | Rate-limited per IP (lookups and merchant portal login/signup). |

**Kept as specified.** eSolutions code `68` stays a final failure, as an earlier test pins it to spec §4.2.2. I briefly considered treating it as unknown and reverted that.

## 3. Evidence

| Suite | What it proves | Level |
|---|---|---|
| `EsolutionsContractTest` | The exact vend message: MTI, processing code, the recorded reference, minor units, currency, merchant and product, vendor number, Basic auth.<br>In-progress and timeout are resolved by resend of the original reference, never by a second vend.<br>A still-unknown outcome is classified UNKNOWN, not failed.<br>Declines use the biller's meaning.<br>Lookups use the payment's currency.<br>Customer information goes through the framework (a USD meter is not payable in ZWG).<br>References are unique and within 20 characters. | Spec fixture |
| `EcoCashContractTest` | The charge request matches the spec: path, correlator, MSISDN, exact amount, currency, notify URL.<br>Immediate decline is definite; 4xx is definite; 5xx is unknown.<br>Callback currency and exact cents.<br>The status query path. | Spec fixture |
| `CircuitBreakerTest` | Breaker state machine; a lost trial expires; the register is well formed, every biller declares lookup, deliver and resend lookup, and every payment provider has authenticated confirmations. | Unit |
| `ProviderIntegrationScenarioTest` (MySQL 8, whole application) | The register matches the loaded adapters both ways.<br>Kill switch over HTTP: 503, nothing sent, payment untouched, pays after resume.<br>Breaker opens after 3 transport failures and never fails a possibly-charged payment.<br>Biller rules apply before payment.<br>**The R57 money fix end to end.**<br>Ops console over HTTP: register, suspend, resume (with reason), biller catalogue and lookup, verification evidence rules. | Integration |

## 4. Reaching the roadmap acceptance

Once a provider gives sandbox access:
1. Point its endpoint property at the sandbox. The register shows `configuredEnvironment: TEST`.
2. Run the flow (for example EcoCash pay → callback → status query, and a ZESA lookup → vend → resend lookup).
3. Record each result: `POST /api/v1/admin/integrations/{code}/verifications` `{environment: SANDBOX, flow, pass, evidence}`.
   - Evidence is mandatory.
   - The flow must be one the provider declares.
4. Raise `verification` to `SANDBOX_VERIFIED` in `register.json` in the same change that cites that evidence. `CircuitBreakerTest` currently fails on any SANDBOX_VERIFIED entry, so that change must also update the test, which keeps it from slipping in unreviewed.

## 5. Still open

- **Real-environment runs** for every provider; they need the owner's sandbox credentials.
- **The ZimSwitch webhook secret.** Callbacks are confirmed by status query meanwhile.
- **Endpoints still pointing at test or internal hosts.** The register reports them:
  - OneMoney: internal IP over http;
  - O'mari: UAT;
  - eSolutions: TEST;
  - ZimSwitch: OPPWA test.
- **Breaker state is per instance.** That is deliberate: each instance protects itself. The kill switch is shared.
- **ZOL, Bulawayo and Moonlight** account formats and ZOL's ZWG support need eSolutions confirmation.
