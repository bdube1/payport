# Phase 6: risk and compliance

_Branch `phase2-payment-lifecycle`, 2026-10-16._

**Scope (roadmap doc 08):**
- rules in observe mode first, then enforcement;
- screening with list management;
- case workflow;
- FIU reports to the confirmed formats;
- dispute timelines per the RBZ rules.

**Acceptance:** "a scenario library (velocity, structuring, sanctions hit, false positive) produces the expected alerts and cases. Access controls are tested."

**Result: met** (`RiskComplianceScenarioTest`, CI run 38895163667: 173 passed, 0 failed).

Two items depend on information PayPort does not have yet. They are built so that the confirmed values can be dropped in, and are marked PROVISIONAL:
- **FIU report format:** reports are produced in a documented provisional structure.
- **RBZ dispute deadlines:** they are configuration.

## What was built

| Area | What it does |
|---|---|
| **Transaction monitoring** | Five rules, evaluated at `/initiate`, before the customer pays:<ul><li>velocity: count per hour;</li><li>velocity: value per day;</li><li>structuring: repeated payments just under the reporting threshold;</li><li>large transaction: a CTR candidate;</li><li>customer sanctions screening.</li></ul>**Every rule starts in OBSERVE** (alert only). |
| **Enforcement** | ENFORCE together with a BLOCK action refuses the payment with **403 `PAYMENT_NOT_ALLOWED`**. The message is neutral and quotes the reference ("contact support"); it never names the rule or list (no tipping off). Nothing is created. |
| **Two-person rule changes** | Changing a rule's mode, action or thresholds is a request by one compliance officer and an approval by another. This uses `platform_approvals`, and the database refuses self-approval. The change is versioned. |
| **Screening lists** | Compliance creates lists (sanctions, PEP, internal) and loads versions. A load replaces the previous version atomically and **re-screens every merchant under review or active**. |
| **Matching** | Two ways:<ul><li>exact identifier: phone on the last 9 digits, ID or registration number;</li><li>fuzzy name: Jaro-Winkler per token, ignoring case, accents, titles and company suffixes.</li></ul>A spelling variant ("Jon Smyth" ~ "John Smith") is flagged on purpose, for a person to clear. |
| **Merchant screening** | The business name, owner and registration number are screened at signup. A hit opens a case and **KYB approval is held** until compliance closes it. KYB staff see only "Compliance review pending". |
| **Alerts** | Written in their own transaction, so the alert for a refused payment survives the refusal. Deduplicated to one per rule, subject and day; repeats are counted. **HIGH alerts open a case automatically** (one open case per subject). |
| **Cases** | Open → investigating (assign, append-only notes) → proposed outcome with rationale → **decided by a different officer** (database CHECK) → closed. The three outcomes:<ul><li>**False positive:** the subject is suppressed for those list entries, so it is not alerted again.</li><li>**No action.**</li><li>**Confirmed suspicious:** an STR draft is prepared automatically.</li></ul> |
| **FIU reports** | STR from a confirmed case (subject, grounds, alerts, the subject's payments). CTR for a period (completed payments at or above the threshold). The preparer cannot approve (database CHECK). After approval, the MLRO records the FIU's acknowledgement reference. |
| **Dispute timelines** | Acknowledgement and resolution deadlines are set when a dispute is opened. Each is shown as ON_TRACK, AT_RISK, BREACHED, MET or MET_LATE, and acknowledgement is recorded. |
| **Access** | New **COMPLIANCE** role. Alerts, cases, rules, lists and FIU reports are visible to COMPLIANCE **only**. OPS, FINANCE, **ADMIN** and customers get 403 (confidentiality, no tipping off). Dispute timelines are for OPS, ADMIN and COMPLIANCE. |

## Scenario library (what the acceptance test proves)

1. **Velocity.** The threshold is changed to 3 per hour (one officer requests, self-approval is refused, a second approves). Three payments: no alert. The fourth: one MEDIUM alert in OBSERVE. All four payments go ahead. A fifth increments the same alert.
2. **Structuring.** USD 850, then 900, then 950 against a USD 1,000 threshold: the third payment raises a HIGH alert, which opens a case.
3. **Sanctions hit.** The listed person's phone pays: HIGH alert, score 1.0, case.
4. **False positive.** Registered customer "Jon Smyth" pays: alert and case. Carol assigns it and proposes a false positive; a rationale is required, and Carol cannot decide her own proposal. Dave closes it. The alert closes, and Jon's next payment raises no alert and no case.
5. **Merchant screening.** "Acme Smuggling (Private) Limited" applies and a case opens. The KYB recommendation is held (409) without revealing why.
6. **Observe → enforce.** Two officers move sanctions screening to ENFORCE. The listed customer is refused (403, neutral, reference quoted, nothing created), the alert shows BLOCKED, and other customers still pay.
7. **STR.** The structuring case is confirmed (four-eyes) and an STR draft is created with the subject's three payments. The preparer cannot approve it, it cannot be marked submitted before approval, and an FIU reference is required. It ends SUBMITTED.
8. **Large transaction and CTR.** A USD 1,500 payment raises a LOW alert and is never blocked. The CTR draft for the day lists it.
9. **Access controls.** Five compliance endpoints are each checked for COMPLIANCE (allowed), OPS, ADMIN, FINANCE and a customer (403), and anonymous (401/403). Even ADMIN cannot switch monitoring off.
10. **Dispute timelines.** Deadlines are set on open. A late one shows BREACHED. Acknowledging it gives MET_LATE, and acknowledging twice gives 409.

## Defect found and fixed along the way

**R64. Every `@PreAuthorize` denial in the API returned 400 instead of 403.** The generic RuntimeException handler caught `AccessDeniedException`. Denials now return 403, or 401 when the caller is anonymous.

## PROVISIONAL values: compliance must confirm before enforcing

| Item | Where | Current placeholder |
|---|---|---|
| Velocity, structuring and large-transaction thresholds | `risk_rules` (changed only with two officers) | USD 1,000 / ZWG 25,000 reporting threshold; 10 payments per hour; USD 2,000 / ZWG 50,000 per day |
| Name-match threshold | `SANCTIONS_CUSTOMER.minScore` | 0.88 |
| FIU STR/CTR format and channel | `ComplianceService.FIU_FORMAT` | `PAYPORT-FIU-PROVISIONAL-v0`: filed manually by the MLRO |
| Dispute deadlines (RBZ) | `payport.disputes.sla.*` | 48 h to acknowledge, 30 days to resolve |
| Case SLA | `payport.compliance.case-sla-days` | 5 days |
| Screening list sources | loaded by compliance | none loaded: UN, OFAC and local lists to be sourced |

## Not yet done

- **Compliance console screens** in `payport-ops` (frontend phase); the API is complete.
- **Customer re-screening on a list update.** Merchants are re-screened. Customers are screened at every payment, so a newly listed customer is caught at their next payment rather than immediately.
- **Name screening for guest customers.** It only applies to registered customers, because guests give no name; guests are screened on their phone number.
