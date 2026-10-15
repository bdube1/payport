@entity RiskRule psp_risk_rules zw.co.payport.gateway.psp.risk
@doc A configurable fraud rule. Types: VELOCITY_COUNT, VELOCITY_AMOUNT, SINGLE_AMOUNT, NEW_COUNTERPARTY_AMOUNT, NIGHT_AMOUNT, DISTINCT_COUNTERPARTIES.
@index uq_risk_rule_code rule_code unique
ruleCode String len=60 notnull
description String len=300 notnull
ruleType String len=40 notnull
appliesTo String len=200 notnull
windowMinutes int notnull
thresholdCount int notnull
thresholdAmountUsdMinor long notnull
score int notnull
action String len=10 notnull
enabled boolean notnull
@updated updatedAt

@entity BlocklistEntry psp_risk_blocklist zw.co.payport.gateway.psp.risk
@doc Hard blocks: MSISDN, DEVICE, IP, NATIONAL_ID (blind index), ACCOUNT. Checked before any rule.
@index uq_block_value entry_type,entry_value unique
entryType String len=20 notnull
entryValue String len=120 notnull
reason String len=500 notnull
addedBy String len=120 notnull
expiresAt LocalDateTime
@created createdAt

@entity RiskEvent psp_risk_events zw.co.payport.gateway.psp.risk
@doc Every assessed transaction, for velocity features and model training. Kept 13 months.
@index idx_rev_subject subject_ref,created_at
@index idx_rev_counterparty counterparty_ref,created_at
txnType String len=40 notnull
channel String len=20
subjectRef String len=60 notnull
counterpartyRef String len=120
amountUsdMinor long notnull
currency String len=3 notnull
amountMinor long notnull
score int notnull
decision String len=10 notnull
reasons String len=1000
@created createdAt

@entity RiskCase psp_risk_cases zw.co.payport.gateway.psp.risk
@doc A fraud or AML case for investigation.
@index idx_case_status status,created_at
@index idx_case_subject subject_ref
caseRef String len=40 notnull unique
caseType String len=20 notnull
subjectType String len=20 notnull
subjectRef String len=120 notnull
triggerRef String len=120
score int notnull
reasons String text
status String len=30 notnull
assignedTo String len=120
outcomeNote String len=2000
closedBy String len=120
closedAt LocalDateTime
@created createdAt
@updated updatedAt
@version

@entity SanctionsEntry psp_sanctions_entries zw.co.payport.gateway.psp.risk
@doc One person / entity on a sanctions or PEP list.
@index idx_sanc_list list_code,active
@index uq_sanc_ext list_code,external_id unique
listCode String len=20 notnull
externalId String len=80 notnull
entryType String len=20 notnull
primaryName String len=400 notnull
normalisedName String len=400 notnull
aliases String text
nationality String len=200
dateOfBirth String len=100
programme String len=200
active boolean notnull
listVersion String len=64 notnull
@updated updatedAt

@entity SanctionsListVersion psp_sanctions_list_versions zw.co.payport.gateway.psp.risk
@doc Audit of each list refresh: which version was in force when a screening was done.
@index idx_slv_list list_code,fetched_at
listCode String len=20 notnull
sha256 String len=64 notnull
entryCount int notnull
source String len=300
status String len=20 notnull
error String len=1000
fetchedAt LocalDateTime notnull

@entity AmlAlert psp_aml_alerts zw.co.payport.gateway.psp.risk
@doc A sanctions/PEP hit or a transaction-monitoring alert for compliance review.
@index idx_aml_status status,created_at
@index uq_aml_dedupe alert_type,subject_ref,dedupe_key unique
alertRef String len=40 notnull unique
alertType String len=40 notnull
subjectType String len=20 notnull
subjectRef String len=120 notnull
subjectName String len=300
dedupeKey String len=120 notnull
matchedEntryId Long
score int notnull
amountUsdMinor Long
details String text
status String len=20 notnull
reviewedBy String len=120
reviewNote String len=2000
decidedAt LocalDateTime
@created createdAt
@version
