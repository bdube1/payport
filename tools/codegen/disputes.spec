@entity DisputeCase psp_dispute_cases zw.co.payport.gateway.psp.disputes
@doc Ledger side of a dispute: what was held from the merchant, the response deadline and the financial outcome.
@index idx_dcase_status status,respond_by
disputeId Long notnull unique
merchantId Long notnull
checkoutSessionId Long notnull
transactionReference String len=60
currency String len=3 notnull
disputedMinor long notnull
heldMinor long notnull
shortfallMinor long notnull
status String len=20 notnull
respondBy LocalDateTime notnull
holdEntryRef String len=40
outcomeEntryRef String len=40
outcome String len=20
decidedBy String len=120
@created createdAt
@updated updatedAt

@entity CustomerRefund psp_customer_refunds zw.co.payport.gateway.psp.disputes
@doc Money owed back to a customer (merchant refund, chargeback, consumer refund) until it is actually paid out.
@index idx_crefund_status status,created_at
@index uq_crefund_source source_type,source_ref unique
refundRef String len=40 notnull unique
sourceType String len=30 notnull
sourceRef String len=120 notnull
merchantId Long
currency String len=3 notnull
amountMinor long notnull
customerMsisdn String len=20
status String len=20 notnull
fundingEntryRef String len=40
payoutEntryRef String len=40
payoutMethod String len=30
payoutReference String len=150
paidBy String len=120
paidAt LocalDateTime
failureReason String len=500
@created createdAt
@updated updatedAt
@version
