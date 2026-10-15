@entity ReconBreak psp_recon_breaks zw.co.payport.gateway.psp.recon
@doc A discrepancy needing a human: records never silently corrected. Unique per (source, type, reference); repeats increment occurrences.
@index uq_break_key source,break_type,reference unique
@index idx_break_status status,last_seen_at
breakRef String len=40 notnull unique
source String len=40 notnull
breakType String len=60 notnull
reference String len=150 notnull
currency String len=3
expectedMinor Long
actualMinor Long
details String text
status String len=20 notnull
occurrences int notnull
firstSeenAt LocalDateTime notnull
lastSeenAt LocalDateTime notnull
assignedTo String len=120
resolution String len=30
resolutionNote String len=2000
resolvedBy String len=120
resolvedAt LocalDateTime
resolutionEntryRef String len=40
@version

@entity BankStatementLine psp_bank_statement_lines zw.co.payport.gateway.psp.recon
@doc One line of a trust / settlement bank account statement, imported for reconciliation.
@index uq_bank_line cash_account,line_hash unique
@index idx_bank_line_match match_status,value_date
importRef String len=40 notnull
cashAccount String len=60 notnull
currency String len=3 notnull
valueDate LocalDate notnull
bankReference String len=150
narrative String len=500
direction String len=6 notnull
amountMinor long notnull
runningBalanceMinor Long
lineHash String len=64 notnull
matchStatus String len=20 notnull
matchedType String len=40
matchedRef String len=150
matchedBy String len=120
matchedAt LocalDateTime
@created createdAt
