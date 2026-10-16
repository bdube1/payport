# Compliance controls: limits, screening, FIU reporting, dispute deadlines

_Branch `phase2-payment-lifecycle`, 2026-10-16._

This covers the owner's items 1 to 5:
- configurable limits, thresholds and velocity rules;
- sanctions screening against the UN Security Council Consolidated List;
- configurable suspicious and cash transaction reports;
- configurable dispute deadlines;
- the controls around all of them.

Migrations: V46 to V48. Tests: `RiskComplianceScenarioTest` and `UnSanctionsListParseTest` (section 7).

**Two principles hold throughout:**
- Nothing new can block a payment until Compliance approves it.
- Nothing about a regulatory value is presented as more certain than its source.

## 1. Parameters: every number is configurable, sourced and status-marked

Every limit, threshold, deadline and matching setting lives in `compliance_parameters`. Each row has:
- the value;
- its unit;
- its **source** (the regulation and section, or the standard it follows);
- a **status**:

| Status | Meaning |
|---|---|
| `SOURCED` | Taken from a published Zimbabwean requirement (section cited). |
| `PROVISIONAL` | International best practice or a working assumption. Applicable requirements still to be confirmed. |
| `CONFIRMED` | A compliance officer has checked it against the source, and a second officer approved that confirmation. |

**How changes are made**
- A change, including confirming a value, is a request by one COMPLIANCE officer that a different officer approves.
- The database refuses self-approval.
- Every step is written to the hash-chained audit log.
- Values are cached for 30 seconds, so a change applies across instances within half a minute.

### Defaults

| Key | Default | Status | Source |
|---|---|---|---|
| `DISPUTE.ACK_HOURS` | 48 h | SOURCED | RBZ Consumer Protection Framework No.1-2017/BSD s6.1.8(a) |
| `DISPUTE.RESOLVE_WORKING_DAYS` | 10 working days | SOURCED | CPF s6.1.8(c) |
| `DISPUTE.PROGRESS_UPDATE_DAYS` | 7 days | SOURCED | CPF s6.1.9(b) |
| `DISPUTE.OUTCOME_NOTICE_DAYS` | 7 days | SOURCED | CPF s6.1.9(c) |
| `DISPUTE.ERROR_RESOLVE_HOURS` | 48 h | SOURCED | CPF s6.5.4 |
| `DISPUTE.ERROR_RBZ_NOTICE_DAYS` | 14 days | SOURCED | CPF s6.5.5 |
| `DISPUTE.CHARGEBACK_RESPONSE_DAYS` | 30 days | **PROVISIONAL** | Card scheme practice; the actual scheme or acquirer rules must be confirmed |
| `DISPUTE.RECORD_RETENTION_YEARS` | 2 | SOURCED | CPF s6.3.5 |
| `STR.DEADLINE_WORKING_DAYS` | 3 working days | SOURCED | RBZ AML/CFT/CPF Guideline 01-2025/BSSFS s3.105 |
| `CTR.THRESHOLD_MINOR` | US$5,000 / ZWG 135,000 | **PROVISIONAL** | Guideline 01-2025 s3.133–3.135 (CTR per FIU directive). The amount follows the FIU casino guidelines (2015) s5. The ZWG equivalent is an assumption. |
| `CTR.AGGREGATION_HOURS` | 24 h | **PROVISIONAL** | FIU casino guidelines s5 |
| `CDD.OCCASIONAL_THRESHOLD_MINOR` | US$5,000 | SOURCED | Guideline 01-2025 s3.36(b) |
| `WIRE.ORIGINATOR_INFO_THRESHOLD_MINOR` | US$1,000 | SOURCED | Guideline 01-2025 s3.36(c), s3.147 (FATF R.16) |
| `AML.RECORD_RETENTION_YEARS` | 5 | SOURCED | Guideline 01-2025 s3.141 |
| `PAYMENTS.RECORD_RETENTION_YEARS` | 10 | SOURCED | RBZ Retail Payment Systems Guidelines 2017 s9.2 |
| `CASE.REVIEW_WORKING_DAYS` | 2 | PROVISIONAL | Internal service level, so that STRs meet s3.105 |
| `SCREENING.MIN_SCORE` | 0.88 | PROVISIONAL | Name-matching best practice; tune on the false-positive rate |
| `SCREENING.LOW_QUALITY_ALIAS_PENALTY` | 0.05 | PROVISIONAL | UN list marks aliases "Low" quality |
| `SCREENING.MAX_LIST_AGE_HOURS` | 24 h | PROVISIONAL | Guideline 01-2025 s3.80–3.83 ("without delay"); hourly checks |
| `SCREENING.MAX_ENTRY_DROP_PERCENT` | 10 % | PROVISIONAL | Tamper or truncation guard |
| `SCREENING.INCOMPLETE_PAYMENT_ACTION` | ALERT | PROVISIONAL | Owner: do not block on unapproved rules |
| `CALENDAR.ZW.HOLIDAYS` | 2026–2027 public holidays | PROVISIONAL | Public Holidays and Prohibition of Business Act; check the Gazette yearly |
| `FIU.REPORTING_ENTITY_ID` | empty | PROVISIONAL | Issued by the FIU on goAML registration |

The PROVISIONAL CTR threshold is **not** the RBZ figure for payment service providers: the guideline leaves the CTR threshold to an FIU directive, which was not found. **The owner or Compliance must confirm it with the FIU.**

## 2. Rules and limits: observe first, enforce only when approved

**Rule types**
- Velocity (count and amount).
- Structuring: several payments each just under the CTR threshold within 24 h.
- Large transaction (at or above the CTR threshold).
- Sanctions.
- Per-transaction limit (`TX_LIMIT`).
- Period limit (`PERIOD_LIMIT`, daily and monthly).
- CDD threshold: an unverified payer at or above the occasional-transaction CDD level.
- Screening health.

Thresholds point to parameters, so changing a parameter changes every rule that uses it.

**Modes:** OFF, OBSERVE (alert only) and ENFORCE.

**Blocking requires all three of:**
- ENFORCE;
- action BLOCK;
- `approved_by` recorded by a second officer.

This is enforced twice:
- in code (`RiskEngine`);
- by a database CHECK (`chk_rr_enforce_approved`), so not even a direct SQL update can switch on an unapproved block.

**Every new rule:**
- including the new limit rules (`TX_LIMIT`, `DAILY_LIMIT`, `MONTHLY_LIMIT`, `CDD_OCCASIONAL`) and `SCREENING_UNAVAILABLE`, starts in **OBSERVE**;
- is created through a two-officer request.

**When a payment is refused** (only by an approved rule), the customer gets 403 `PAYMENT_NOT_ALLOWED` with neutral wording: no tipping off (Guideline 01-2025 s3.127/3.129; MLPC Act s31(2)).

## 3. Sanctions screening: UN Security Council Consolidated List

**Source**
- Fetched from `https://scsanctions.un.org/resources/xml/en/consolidated.xml`, every hour and on demand.
- Redirects are followed by hand, and only to allow-listed hosts: `scsanctions.un.org` and the UN's blob storage host. HTTPS only (plain http is refused in production). Size-capped.

**Verification before a version is used**
- SHA-256 of the file. An unchanged file only records "checked".
- XXE-safe parsing: DTDs and external entities are refused.
- The root must be `CONSOLIDATED_LIST`, with a `dateGenerated` and at least one entry.
- If the entry count drops by more than `SCREENING.MAX_ENTRY_DROP_PERCENT`, the file is **QUARANTINED**: the previous version stays active and an alert is raised.
- Every fetch (NEW_VERSION, UNCHANGED, FAILED, QUARANTINED) is recorded with host, hash, generation time and error.

**What is loaded:** individuals and entities with:
- reference number;
- names and original script;
- aliases ("Low" quality aliases are marked and scored lower);
- document numbers;
- nationality and date of birth.

**After a new version loads**, every pending or active merchant and every active customer is re-screened.

**Screening fails closed.** A required list that is not loaded, or not checked within `SCREENING.MAX_LIST_AGE_HOURS`, makes every screening **INCOMPLETE**. INCOMPLETE is never CLEAR:
- **KYB approval is refused**, at both the recommend and the decide step: "Sanctions screening could not be completed…".
- **Payments raise a `SCREENING_UNAVAILABLE` alert.** They are blocked only if Compliance has approved an enforcing sanctions rule and set `SCREENING.INCOMPLETE_PAYMENT_ACTION` to BLOCK.

**Auditable**
- Every screening writes a `screening_runs` row: subject, context, outcome (CLEAR, POTENTIAL_MATCH or INCOMPLETE), the **list versions used**, the reason when incomplete, and the matches.

**False positives**
- A potential match opens an alert, which goes into a case.
- When two officers close the case as FALSE_POSITIVE, that subject's matches against those specific list entries are suppressed, linked to the case.
- A new list entry, or a changed reference, is screened afresh.

## 4. Suspicious and cash transaction reports (FIU, goAML)

**Templates**
- FIU Zimbabwe receives reports through goAML (Guideline 01-2025 s3.106). The FIU's own template was not available, so PayPort's templates are built to the goAML report structure: `report`, `rentity_id`, `submission_code`, `report_code`, `reporting_person`, `reason`, `action`, `transaction`, `report_indicators`.
- They also follow FATF and Egmont good practice: who, what, when, where, why it is suspicious, and what was done.
- Templates (`fiu-templates/payport-str-v1.json`, `payport-ctr-v1.json`) are **versioned data**. A new version is created as DRAFT and activated by a different officer; the previous version is retired.

**Each field declares its source**
- A parameter (e.g. `FIU.REPORTING_ENTITY_ID`).
- Case data.
- Subject data.
- Officer details.
- Manual entry.
- Some fields are filled automatically but may be overridden (for example, a guest payer's name).
- Automatic fields cannot be edited. The narrative needs at least 200 characters.

**Workflow**
1. A case closed by two officers as CONFIRMED_SUSPICIOUS creates an **STR draft**, due 3 working days after the suspicion was formed.
2. CTRs are prepared per customer per day at or above the threshold.
3. Drafts can be edited only while DRAFT.
4. **Approval needs all of:**
   - a designated `REPORT_APPROVER`: granted by an administrator and approved by a compliance officer other than the holder;
   - not the preparer;
   - every required field complete.
5. After approval the report is frozen.
6. "Submitted" records the FIU's goAML reference.

**Output:** goAML-structured XML. The reporting-entity ID shows `TO-BE-ISSUED-BY-FIU` until the FIU issues it.

## 5. Dispute deadlines

**Types**

| Type | Deadlines |
|---|---|
| COMPLAINT | Acknowledge in 48 h; resolve in 10 working days (weekends and Zimbabwe public holidays skipped). |
| ERROR | Resolve in 48 h. If not, an RBZ notice is due within 14 days. |
| CHARGEBACK | 30 days, PROVISIONAL. |

**When the resolution date passes:** progress updates are due every 7 days, and an outcome notice is due within 7 days of resolution.

**What each deadline shows**
- Its rule, the rule's status and its source, so **provisional settings are visible wherever the deadline is shown** (API and ops console).
- Its state: ON_TRACK, AT_RISK, BREACHED, MET or MET_LATE.

Communications (acknowledgement, progress, outcome, RBZ notice) are recorded with who sent them and when.

## 6. Controls

| Control | Where |
|---|---|
| **Role-based access** | Only `COMPLIANCE` reaches `/api/v1/admin/compliance/**`. ADMIN deliberately cannot: it can only request authority grants. Denials are 403 (401 when anonymous). The ops console hides these screens from other roles. |
| **Two-person approval** | Required for: rule changes and creations; parameter changes and confirmations; case closures; template activation; authority grants; report approval (approver ≠ preparer). The database CHECK on `platform_approvals` forbids self-approval. |
| **Audit trail** | `compliance_audit_log` is a SHA-256 hash chain. Each entry includes the previous hash, and a head row is updated under a lock. `GET /audit/verify` re-computes the whole chain and reports the first broken entry. |
| **No live blocking on unapproved rules** | Code check plus the database CHECK `chk_rr_enforce_approved`. |
| **No tipping off** | Neutral customer messages. Report screens carry the warning. |

## 7. Evidence

| Test | Proves |
|---|---|
| `RiskComplianceScenarioTest.unConsolidatedList_…` | **Fetch:** a UN-format file is fetched through a redirect from a stub host, with SHA-256 and generation time recorded; an unchanged file only records "checked".<br>**Matching:** an alias match on a registered customer is recorded with list versions.<br>**Refusals:** a truncated file is QUARANTINED (the active version is kept); non-XML and HTTP 503 both give FAILED.<br>**Stale list:** INCOMPLETE; the payment proceeds with a SCREENING_UNAVAILABLE alert; KYB is refused, then succeeds once the list is fresh again.<br>The audit chain is intact. |
| `RiskComplianceScenarioTest.newRulesStartInObserve_…` | A new rule needs two officers and starts in OBSERVE.<br>OBSERVE never blocks.<br>The database refuses ENFORCE without approval.<br>Parameters show source and status; confirming needs a second officer, never self-approval. |
| `RiskComplianceScenarioTest.disputeTimelines_…` | **Deadlines:** 48 h acknowledgement, and 10 working days across Christmas and New Year (21 Dec 2026 → 7 Jan 2027).<br>**Overdue disputes:** progress updates become due, and acknowledgement is MET_LATE.<br>**Updates:** an empty update is refused; the outcome notice only after resolution.<br>Settings are exposed. |
| `RiskComplianceScenarioTest` (STR flow) | **Preparing:** a draft is created from a confirmed case; automatic fields refuse edits (400); the parameter is set via two officers.<br>**Approval refused:** for the preparer; for an officer without REPORT_APPROVER; for an incomplete report.<br>**Grants:** an ADMIN request, never approved by the holder.<br>**After approval:** edits are refused; submission is recorded; the audit trail is complete. |
| `UnSanctionsListParseTest` | **Parsing:** individuals, entities, aliases (Low marked), original script, document numbers, DOB.<br>**Refusals:** DTD/XXE, wrong documents, a file without `dateGenerated`, and a file without entries.<br>**Audit hash:** sensitive to every field and to the previous hash. |

## 8. Open items for the owner and Compliance
1. **Confirm the CTR threshold and aggregation period** with the FIU. They are PROVISIONAL.
2. **Register PayPort on goAML** and set `FIU.REPORTING_ENTITY_ID` through the two-officer parameter change.
3. **Confirm the card scheme or acquirer chargeback deadline.** It is PROVISIONAL.
4. **Check the holiday calendar** against the Government Gazette each year.
5. **Appoint at least two COMPLIANCE users**, and have an administrator request `REPORT_APPROVER` for the officer(s) who will approve reports.
6. **Review OBSERVE alerts before enforcing anything.** Decide whether stale screening should block payments: this is `SCREENING.INCOMPLETE_PAYMENT_ACTION` plus an approved enforcing sanctions rule.
7. **Consider adding other required lists** (for example any Zimbabwe domestic designations) as additional lists with `required_for_clear`.
