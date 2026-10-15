@entity RegulatoryReport psp_regulatory_reports zw.co.payport.gateway.psp.reporting
@doc A generated regulatory / tax return with its data, hash and submission trail. Reviewed by a second person before submission.
@index idx_rr_type_period report_type,period_start
reportRef String len=40 notnull unique
reportType String len=40 notnull
periodStart LocalDateTime notnull
periodEnd LocalDateTime notnull
status String len=20 notnull
payloadJson String text notnull
csv String text
sha256 String len=64 notnull
generatedBy String len=120 notnull
reviewedBy String len=120
reviewedAt LocalDateTime
submittedBy String len=120
submittedAt LocalDateTime
submissionRef String len=150
@created createdAt
@version
