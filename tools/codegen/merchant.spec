@entity MerchantProfile psp_merchant_profiles zw.co.payport.gateway.psp.merchant
@doc PSP controls for a merchant (1:1 with the legacy merchants row): verification, risk class, fund policy, suspension.
@index idx_mprof_verification verification_status
merchantId Long notnull unique
merchantType String len=20 notnull default="MERCHANT"
parentMerchantId Long
verificationStatus String len=20 notnull
verifiedBy String len=120
verifiedAt LocalDateTime
kycReviewDueAt LocalDateTime
riskClass String len=20 notnull
riskReason String len=500
mcc String len=4
holdDays int notnull
reserveBps int notnull
reserveDays int notnull
settlementFrequency String len=20 notnull default="DAILY"
minPayoutMinor long notnull
suspensionScope String len=20 notnull default="NONE"
suspensionReason String len=500
suspendedBy String len=120
suspendedAt LocalDateTime
@created createdAt
@updated updatedAt
@version

@entity MerchantProduct psp_merchant_products zw.co.payport.gateway.psp.merchant
@doc A payment method / currency a merchant is approved to accept. Nothing is collectable unless approved here.
@index uq_mprod merchant_id,payment_method_code,currency unique
merchantId Long notnull
paymentMethodCode String len=50 notnull
currency String len=3 notnull
status String len=20 notnull
approvedBy String len=120
approvedAt LocalDateTime
@created createdAt
@updated updatedAt

@entity MerchantLimit psp_merchant_limits zw.co.payport.gateway.psp.merchant
@doc Per-merchant transaction limits by currency. PER_TXN_MIN, PER_TXN_MAX, DAILY_AMOUNT, MONTHLY_AMOUNT, DAILY_COUNT.
@index uq_mlimit merchant_id,currency,limit_type unique
merchantId Long notnull
currency String len=3 notnull
limitType String len=20 notnull
limitValue long notnull
approvedBy String len=120
@created createdAt
@updated updatedAt

@entity MerchantPayoutAccount psp_merchant_payout_accounts zw.co.payport.gateway.psp.merchant
@doc Where a merchant's settlements are paid. Encrypted at rest; changes need four-eyes verification and a cooling-off period.
@index idx_mpayout_merchant merchant_id,currency,status
payoutRef String len=40 notnull unique
merchantId Long notnull
currency String len=3 notnull
method String len=20 notnull
bankName String len=120
bankCode String len=40
branchCode String len=40
accountName String len=200 notnull
accountNumber String len=600 encrypted
accountNumberLast4 String len=4
mobileNumber String len=600 encrypted
status String len=30 notnull
createdBy String len=120 notnull
verifiedBy String len=120
verifiedAt LocalDateTime
coolingOffUntil LocalDateTime
@created createdAt
@updated updatedAt

@entity FeeAgreement psp_fee_agreements zw.co.payport.gateway.psp.fees
@doc Commercial terms. merchantId null = platform default; paymentMethodCode null = any method. Activated via four-eyes approval.
@index idx_fee_lookup merchant_id,currency,status
agreementRef String len=40 notnull unique
merchantId Long
paymentMethodCode String len=50
currency String len=3 notnull
percentBps int notnull
fixedMinor long notnull
minFeeMinor long notnull
maxFeeMinor long notnull
effectiveFrom LocalDateTime notnull
effectiveTo LocalDateTime
status String len=20 notnull
createdBy String len=120 notnull
approvedBy String len=120
notes String len=500
@created createdAt
@updated updatedAt

@entity TaxRate psp_tax_rates zw.co.payport.gateway.psp.fees
@doc Tax rates set by finance (VAT on fees, IMTT). Never hard-coded: rates change with the Finance Act.
@index idx_tax_lookup tax_code,currency,status
taxCode String len=20 notnull
currency String len=3 notnull
rateBps int notnull
capMinor long notnull
thresholdMinor long notnull
merchantBorne boolean notnull
effectiveFrom LocalDateTime notnull
effectiveTo LocalDateTime
status String len=20 notnull
createdBy String len=120 notnull
approvedBy String len=120
notes String len=500
@created createdAt
