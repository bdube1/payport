# Phase 0: Financial, security, database, integration and regulatory risk register

**Severity:**
- **Critical:** money or customer data at risk now.
- **High:** must be fixed before the PSP takes any merchant's funds through the ledger.
- **Medium:** fix within the relevant phase.
- **Low:** track.

"Where" is **G** for the existing gateway and **P** for the `psp-foundation` branch.

## Critical: act now, independent of the roadmap

| ID | Risk | Where | Evidence | Containment now | Proper fix |
|---|---|---|---|---|---|
| R1 | Payment links and dashboard checkouts are simulated as paid without charging the customer, then become settleable | G, P | [Observed] `CheckoutController.pay`: `isTestMode = true` when `apiKeyId` is null; `PaymentLinkService` passes `null` | Check production data; disable payment links, or treat no-key sessions as live | Explicit `livemode` on every session, from the creating credential or merchant mode; settlement and ledger exclude test-mode; tests for each creation path |
| R2 | Anyone can upload KYB documents into, and read review notes of, any merchant application by guessing `MER-yyyyMMdd-NNNN` | G | [Observed] `PublicMerchantTrackingController` is `permitAll` | Add a high-entropy tracking token (emailed) or require the applicant login; rate-limit | Applicant accounts; document upload only by an authenticated applicant; audit |
| R3 | Unauthenticated, enumerable transaction lookup | G | [Observed] `/api/v1/transactions/*` is `permitAll` | Return minimal fields; rate-limit | Per-transaction access token issued to the paying browser |
| R4 | Forged EcoCash/OneMoney callbacks can mark payments paid | G (fixed in P, unverified) | [Observed] | Deploy only the callback verify-by-query fix, after testing against the provider sandbox | Phase 2 callback framework |

## High

| ID | Risk | Where | Evidence | Mitigation / phase |
|---|---|---|---|---|
| R5 | No financial source of truth; balances derived from transaction rows; legacy settlement uses `double` and has no approval or proof | G | [Observed] | Phase 1 ledger; Phase 3 settlement |
| R6 | Timeout treated as failure (EcoCash reconciler gives up to FAILED after 30 min) | G | [Observed] | Phase 2. The branch has a fix (unverified) |
| R7 | Append-only ledger defeated by `TRUNCATE` / `DROP TRIGGER` under the app's DB privileges | P | **[Tested]** | Phase 1: least-privilege runtime user, separate migration user, external anchoring of chain seals |
| R8 | Ledger balance not enforced by the database | P | **[Tested]** | ADR-0004 decision; continuous detection is mandatory either way |
| R9 | V32/V33 half-apply under Flyway when binlog is on and the user lacks SUPER | P | **[Tested]** | Phase 1: precondition check before any DDL; DBA-applied trigger script |
| R10 | 19k lines of PSP code never executed; untested controls (merchant gates, routing, fraud blocking) wired into the live checkout path | P | [Observed] | **Do not deploy `psp-foundation` as is.** Re-enter it module by module behind feature flags, in shadow mode first (ADR-0002) |
| R11 | Schedulers are not cluster-safe; a second instance would double-run settlement drafting, recon and resolvers | G, P | [Observed] | Phase 1 job framework with DB leases |
| R12 | In-memory fulfilment queue loses jobs on restart (airtime/bills paid but not delivered) | G | [Observed] | Phase 2 durable jobs. The branch has a recovery sweeper (unverified) |
| R13 | Merchant webhook SSRF: merchant-supplied URL can target internal hosts or metadata endpoints | G | [Observed] | Phase 4: https only, resolve and block private/link-local ranges, egress proxy. **Mostly fixed (Phase 4):** `WebhookTargetGuard` (https, public addresses re-checked on every delivery, no redirects, port rules, metadata always refused); egress proxy still open (infrastructure) |
| R14 | Secrets in plaintext: webhook secrets, `.env` files on the VM; DB without TLS (`useSSL=false`) | G | [Observed] | Phase 1 secrets manager; encrypt webhook secrets; TLS to DB. **Partly fixed (Phase 4):** webhook secrets AES-256-GCM at rest (`SecretBox`, `PAYPORT_DATA_KEY`), legacy values re-sealed on use; secrets manager and DB TLS still open |
| R15 | MySQL and Redis published on host ports in `docker-compose.yml` | G | [Observed] | Confirm the real deployment; never expose DB ports publicly |
| R16 | No meaningful automated tests; every change to money code is unverified | G, P | [Observed] | Quality gates from Phase 1 (Testcontainers MySQL, simulator contract tests) |
| R17 | Build cannot be reproduced in this environment; Spring Boot 3.2.0 out of OSS support | G, P | **[Tested]** | CI with real `mvn verify`; upgrade to a supported Boot line in Phase 1 |
| R18 | QR implementation does not follow the RBZ QR Guidelines (scheme MAI ID via National Switch; closed-loop QR to cease) | P | [Observed] vs the guidelines effective 11 Mar 2026 | Do not launch QR until a scheme membership or acquirer route is agreed |
| R19 | PayPort's licence position unknown: the e-money wallets, agents and FX built on the branch may be activities PayPort is not authorised to perform | P | [Not verified] | Operating-model decision (doc 07) before Phase 8 products |
| R20 | Trust/safeguarding arrangement unknown: who holds collected funds, and in which account | G, P | [Not verified] | Operating-model decision; ledger cash accounts must mirror the real accounts |

## Medium

| ID | Risk | Where | Mitigation |
|---|---|---|---|
| R21 | Duplicate sources of truth: refunds, disputes, reconciliation and settlement exist in both the gateway and the PSP | P | ADR-0003: one owner per concept; gateway records become projections or are retired |
| R22 | Single status field mixes payment, fulfilment and refund states | G | Phase 2: separate payment attempt, posting, fulfilment and settlement states |
| R23 | Zimbabwe assumptions hard-coded (74 PSP code lines contain ZW-specific literals) | P | Phase 1 country/currency reference data; Phase 7 full model |
| R24 | `ddl-auto=update` and `allow-bean-definition-overriding=true` hide schema drift and wiring errors | G | Phase 1: `validate`, overriding off |
| R25 | Docker build broken; deployment is a single VM via `start.sh` | G | Phase 1 reproducible build; Phase 9 resilience |
| R26 | Placeholder business values (tier limits, tariffs, routing costs, fraud thresholds) could reach production | P | Values must come from approved, versioned config; refuse to start with placeholders in production mode |
| R27 | Tax handling: IMTT 2% USD / 1.5% ZiG and VAT 15.5% from 1 Jan 2026 (Finance Act No. 7 of 2025, as reported); whether IMTT is charged by PayPort or by the paying institution depends on the flow | P | Confirm with a tax adviser; effective-dated, approved tax configuration |
| R28 | Data protection: Cyber and Data Protection Act (POTRAZ). Controller licensing and a DPO (SI 155 of 2024), breach notice to POTRAZ within 24 h, notice before cross-border transfers | G, P | Regulatory register (doc 07); hosting decision |
| R29 | RBZ incident reporting for QR participants: security breaches within 3 hours; fraud reports within 24 hours | P | Incident runbook timings once in scope |

## Added during Phase 0.5

| ID | Risk | Where | Status |
|---|---|---|---|
| R3 (extended) | The public transaction response included `fulfilmentData`, which for ZESA purchases **is the electricity token**. Anyone enumerating references could take other customers' tokens. | G | Fixed on `phase0.5-containment` |
| R35 | `/api/v1/transactions/my-history` logged every request header (cookies, API keys) at INFO | G | Fixed on `phase0.5-containment` |
| R36 | CORS allows `http://localhost:3000-3002/4321` with credentials in the production configuration | G | **Fixed (Phase 9)**: localhost origins only outside production (`PAYPORT_ENV`); extra https origins by configuration; unit-tested |
| R37 | EcoCash adaptor `queryStatus()` always returns UNKNOWN; only `queryTransaction()` works | G | Worked around by `PaymentStatusVerifier`; fix the adaptor contract in Phase 5 |

## Added during Phase 1

| ID | Risk | Status |
|---|---|---|
| R38 | Ledger concurrency: a no-negative check that reads the transaction snapshot instead of the locked row allows overdrafts (163 of 200 spends against funds for 100) | **Found and fixed** before release; regression-tested by `LedgerConcurrencyTest` |
| R39 | Refunds recorded after a settlement are never recovered from the merchant (legacy ignores settled sessions) | Open: business decision (claw back or write off). The ledger records these as shadow failures. |
| R40 | Customer refunds are marked COMPLETED with no record that the money reached the customer | Open: Phase 3 refund payout tracking |
| R42 | `RestTemplateConfig` routes EcoCash API traffic through a **Tor SOCKS proxy on localhost:9050**. This is a reliability dependency (no Tor means no EcoCash) and a compliance and security concern for a payment integration | Open: confirm why with the team; replace with an approved egress route |
| R43 | Spring Boot 3.x out of open-source support (3.5 ended 30 Jun 2026) | **Fixed on `phase1-spring-boot-upgrade`** (4.1.1) |
| R44 | CI could pass while Docker-backed suites silently skipped | **Fixed**: required-test skip guard |
| R41 | Chain seals are only tamper-evident if the latest seal is exported outside the database | Open: ops task in LEDGER_OPERATIONS.md section 3 |

## Added during Phase 2

| ID | Risk | Status |
|---|---|---|
| R45 | ZimSwitch webhooks were checked for structure only: a forged body with the success result code marked a card payment paid | **Fixed on `phase2-payment-lifecycle`**: ZimSwitch callbacks are confirmed by status query |
| R46 | Staff `/api/v1/test` endpoints buy ZESA tokens with PayPort's float and mark any payment PAID, with no provider, audit row or second approver | **Removed** (owner decision: never skip payment) |
| R47 | The provider was called inside the DB transaction: an exception rolled the payment back to INITIATED, allowing a second push | **Fixed** (commit before call) |
| R48 | Read timeouts and 5xx responses marked payments FAILED with a failure SMS while the customer may have been charged | **Fixed** (UNKNOWN outcome, resolved by query) |
| R49 | Fulfilment "automatic retries" never ran: the retry found the payment in FULFILMENT_FAILED and skipped it | **Fixed**: automatic retry of *definite* failures works, but is off by default pending per-provider review |
| R50 | Econet delivery query was never implemented although the spec has `get_transaction_status` | **Fixed**: implemented; also fixed a null-code crash in its parser |
| R51 | The audit log used a separate transaction: an extra pooled connection per status change, and audit rows that survived a rolled-back change | **Fixed**: audit joins the caller's transaction |

| R52 | ZimSwitch (OPPWA) webhooks are AES-256-GCM encrypted per the docs; the handler expected plain JSON, so real webhooks could never be read | **Fixed**: decryption (`ZIMSWITCH_WEBHOOK_SECRET`); confirmation stays by status query |
| R53 | OPPWA status mapping treated every unrecognised code as FAILED and all `800.400.*` as pending (includes 3-D Secure rejections) | **Fixed**: published code groups; undocumented codes are UNKNOWN |
| R54 | NetOne delivery query reused its agent reference, which NetOne rejects (HTTP 429) after the first query; HTTP 400 answers were treated as transport errors | **Fixed** |
| R55 | e-Solutions "in progress" (09) and "still awaiting confirmation" were recorded as delivery failures, so an ops retry could buy a second ZESA token | **Fixed**: UNKNOWN, resolved by resend/lookup as the spec requires |
| R56 | A provider success after the customer was told "timed out" could be delivered to a customer who already paid again | **Fixed** (owner decision 2026-10-16): never delivered; refund only (`PAID_AFTER_TIMEOUT` accepts only refund resolutions; no approve path exists) |
| R57 | eSolutions billers (ZESA, ZOL, Bulawayo, Nyaradzo, Moonlight): the vendor reference sent was never stored, so the lookup after an unknown outcome sent the PayPort reference, which eSolutions never saw. An issued token could be recorded as not delivered (and refunded). Lookup references were time-to-the-second, so two in one second collided | G | [Observed] | **Fixed (Phase 5)**: per-attempt delivery reference recorded before sending and used for the lookup; unique lookup references (`ProviderIntegrationScenarioTest`, `EsolutionsContractTest`) |
| R58 | All four frontends read `response.data.message`; the API sent only `error.message`, so staff and customers never saw the real reason (incl. the provider's words) | G | [Observed] | **Fixed in the backend (Phase 5)**: error responses also carry a top-level `message` |
| R59 | Consumer web treats `PAYMENT_TIMEOUT` as 'still checking'; mobile waits for a non-existent `TIMEOUT` status, so a timed-out payment spins forever. Neither sends `X-Transaction-Token` or `Idempotency-Key` | Frontends | [Observed] | Open: `docs/frontend/FRONTEND_INTEGRATION_PLAN.md` §1 (use `outcome`) |
| R60 | Public bill-account lookup returns the account holder's name for any account number (enumeration) | G | [Observed] | Mitigated (Phase 5): rate-limited per IP; owner decision pending on login/captcha |
| R61 | No way to stop traffic to a failing or compromised provider except a deploy | G | [Observed] | **Fixed (Phase 5)**: audited kill switch per provider (shared across instances) and circuit breakers, both refusing before anything is sent |
| R62 | EcoCash callbacks were always recorded as USD and amounts converted via floating point; a synchronous EcoCash decline was treated as 'sent' (customer waited out the window) | G | [Observed] | **Fixed (Phase 5)**: callback currency, exact decimal amounts, immediate declines definite (`EcoCashContractTest`) |
| R63 | Production could start with webhook secrets encrypted under a key derived from the JWT secret, or with SSRF test switches on | G | [Observed] | **Fixed**: `PAYPORT_ENV` defaults to production, which requires `PAYPORT_DATA_KEY` and refuses the switches |
| R64 | Every `@PreAuthorize` denial returned HTTP 400 (the generic RuntimeException handler caught `AccessDeniedException`) | G | [Observed] | **Fixed (Phase 6)**: 403, or 401 when anonymous |
| R65 | No transaction monitoring, screening or case management; no FIU reporting | G | [Observed] | **Built (Phase 6)**, OBSERVE mode. Thresholds, the FIU format and the dispute deadlines are PROVISIONAL until compliance confirms them; screening lists still to be sourced |
| R66 | Customers were never told of failures or timeouts outside the app (SMS is success-only by owner decision) | G | [Observed] | **Fixed in the backend**: push notifications for every final outcome; the mobile app must register its token (frontend plan) |
| R67 | Sanctions screening relied on manually loaded lists with no update checks; a missing or stale list was indistinguishable from "no match" | G | [Observed] | **Fixed (compliance batch)**: UN Consolidated List fetched hourly over allow-listed HTTPS with hash, quarantine and audit; missing or stale required lists make screening INCOMPLETE, never CLEAR (KYB refused, payments alerted) |
| R68 | The ops console and consumer web app logged the user out on any 403, so a customer refused with PAYMENT_NOT_ALLOWED (or staff denied one action) lost their session | G | [Observed] | **Fixed in frontend PRs** (ops #1, consumer #1): only 401 logs out |
| R69 | Currencies hard-coded ("USD or ZWG") in checkout, links and enablements; no country or entity model, so a second market meant code changes | G | [Observed] | **Fixed (Phase 7)**: market configuration with startup invariants; a sandbox market can never take live money |
| R70 | Mobile app treated a non-existent status 'TIMEOUT' as final (timed-out payments spun forever); its bill lookup sent the wrong field names; the push token was never registered | G | [Observed] | **Fixed in mobile PR #1** (outcome-driven screen, biller lookup, token register/revoke) |
| R71 | Official ISO 20022 schemas could not be downloaded by CI (iso20022.org refuses CI runners) | G | [Observed] | **Resolved**: base schemas kept in the repository with provenance and SHA-256 pins (cross-checked copies); the owner's HVPS+ camt.053 usage guideline added as an optional market setting (licensed: internal use only) |
| R72 | Customer phone validation hard-coded to Zimbabwe (+263) in the payment and sign-up APIs, so a second market switched on could not serve its own customers | G | [Observed] | **Fixed**: E.164 at the API, then the numbering plan of any non-suspended market from the market settings |
| R73 | Markets, currencies and channels could only be changed in code or configuration files | G | [Observed] | **Fixed**: operated from the ops console with immediate switch-off and two-person (compliance) switch-on, audited |
| R74 | KYB checklist let a person tick "sanctions screened" | G | [Observed] | **Fixed**: the item is the server's CLEAR screening result (with its run id); approval is refused otherwise |
| R75 | Three admin controllers (ops dashboard, transaction search and export, customer statements, refunds, fulfilment retry, float thresholds, provider health and float balances) had no role guard: any signed-in customer could call them; refund and retry took the operator name from the request body | P | [Tested] | **Fixed (Phase 9)**: role guards, admin and merchant prefixes also closed by URL, operator from the login; the authorization matrix test covers every admin and merchant route on each build |
| R76 | `org.apache.xmlrpc` 3.1.3 (Econet airtime adapter only), end-of-life with no fixed release. **CVE-2019-17570** (CVSS 3.1 **9.8**, critical): the client deserialises a server-supplied `faultCause` with `ObjectInputStream` in `XmlRpcResponseParser`, so a malicious or impersonated server can run code in PayPort. **CVE-2016-5004 / GHSA-r2pg-w96p-pcpj** (CVSS 3.0 **6.5**, moderate, availability): decompression ("zip bomb") denial of service through Content-Encoding. The CI scanner reports only the second: CVE-2019-17570 is filed under the `xmlrpc` artifact, not `xmlrpc-client`/`xmlrpc-common` (scanner blind spot, R82). Exploitability: needs control of the Econet endpoint or of the network path; the endpoint is configurable and the transport is **plain HTTP**, so anyone on that path (or a mis-set `ECONET_HOST`) can answer. The private network alone is not treated as sufficient. | G | [Tested] | **Mitigated, not closed (Phase 10)**: every response is read through `XmlRpcResponseGuard` before the library parses it: 256 KiB cap (bounds a decompression bomb), refuses `faultCause`, serialised-Java markers, DOCTYPE/ENTITY; extensions, exceptions and gzip are off in the client config (`Phase10UnitTest`). Residual: a parser flaw not covered by the screen. **Required before Econet traffic in production**: TLS or an IPsec/VPN tunnel to Econet with the server identity pinned; then replace the library with a minimal XML-RPC client (no Java serialisation). Owner: engineering lead; acceptance of the residual risk: PayPort owner (**not yet signed**). Review: 2027-01-22 or before Econet go-live, whichever is first. |
| R77 | Known advisories in dependencies (POI, commons-compress, Jackson 2 and 3, Tomcat, Bouncy Castle) | G | [Tested] | **Fixed (Phase 9)** by version overrides; `dependency-scan` CI job reports new advisories on every build |
| R78 | Phase 8 lines (wallets, agents, POS, WhatsApp, new-market rails) carry customer money once switched on | G | [Tested] | Built off by default behind licence answers, ledger no-negative rules, PIN confirmation, four-eyes float and rules; switch-on runbook `docs/runbooks/SWITCH_ON.md` |
| R79 | Production config sets `spring.jpa.hibernate.ddl-auto=update` while Flyway owns the schema: Hibernate may alter production tables at start-up outside migration review | G | [Observed] `application.properties:24` | **Open (Phase 10)**. Evidence that `validate` works: `OperationalReadinessScenarioTest` boots the whole application with `validate` on the Flyway schema (V1–V52). Recommended change: `validate` (or `none`) in production, with owner approval because it changes start-up behaviour. Owner: engineering lead. Review: before the next production deploy |
| R80 | OneMoney adapter defaults to a plain-HTTP private address (`ONEMONEY_BASE_URL` default `http://172.28.255.21:8087`); Econet XML-RPC also uses plain HTTP | G | [Observed] | **Open**: production must set the endpoints explicitly and carry provider traffic over TLS or a VPN tunnel (see R76). Payment results are only taken from authenticated status queries, so a forged callback cannot mark a payment paid, but a spoofed status-query answer could. Owner: engineering lead with the network provider. Review: before provider go-live |
| R81 | Transaction lookups without the per-payment access token return the redacted public view (`TRANSACTION_TOKEN_ENFORCE=false` by default, R3 containment) | G | [Tested] | **Open by design** until the frontends send the token everywhere; then set `TRANSACTION_TOKEN_ENFORCE=true`. Phase 10 test: another signed-in customer never sees the payer's phone. Owner: engineering lead. Review: with the frontend release |
| R82 | The dependency scanner (OSV, by Maven artifact) missed CVE-2019-17570 because it is filed under a different artifact name; "1 advisory" understated the real exposure | G | [Observed] | **Open**: keep the manual review of end-of-life libraries in each phase report; add a second scanner (e.g. OWASP Dependency-Check, CPE-based) in CI when its database is reachable from the runners. Owner: engineering lead. Review: 2027-01-22 |

**Phase 2 status of earlier risks:**
- **R4 / R6:** closed by design on the Phase 2 branch. The callback inbox and query-confirmed callbacks cover R4; the durable resolver, with no time-based failure, covers R6.
- **R11 (schedulers):** the payment resolver now runs under a lease, and fulfilment jobs are claimed atomically.
- **R12 (lost fulfilment jobs):** fixed by durable jobs.
- **R22 (single status field):** partly addressed. Attempts, checks and jobs now carry their own states. The `transactions.status` field is unchanged for compatibility.

## Low

| ID | Risk |
|---|---|
| R30 | Unused Redis container; broken Docker health check |
| R31 | AI assistant shares the API process and quota with payment traffic |

## Correction to my earlier delivery

The `psp-foundation` documents (`PSP_ARCHITECTURE.md`, `INTEGRATION_STATUS.md`) label many features **"Built"**. Under your definition of done they are **implemented but not verified**, and the QR module is non-conforming. Those documents now carry a notice pointing to this register.
| R83 | InnBucks adapter built without a published API specification (endpoint paths and field names configurable; status by query) | G | [Spec-only] | **Open**: SPEC_ONLY in the integration register. Before go-live, set `INNBUCKS_*` from the InnBucks merchant pack, run a sandbox payment, correct paths/fields in configuration and record the evidence. Owner: engineering lead with InnBucks. Review: before InnBucks go-live |
| R84 | Direct USSD gateways of Econet, NetOne and Telecel accept a request with the network's access key in the URL or from an allowed IP address; the message formats follow the cashmet integrations, not a network specification | G | [Tested on fixtures] | **Open**: confirm each network's format on its test platform; prefer IP allow-lists over URL keys where the network calls from fixed addresses (URL keys can appear in access logs). Owner: engineering lead with each network. Review: before USSD go-live |
