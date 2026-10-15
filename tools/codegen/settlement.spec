@entity SettlementBatch psp_settlement_batches zw.co.payport.gateway.psp.settlement
@doc A set of payouts to merchants, approved as a unit by someone other than its creator.
@index idx_sbatch_status status,created_at
batchRef String len=40 notnull unique
currency String len=3 notnull
status String len=20 notnull
cashAccount String len=60 notnull
cutoffAt LocalDateTime notnull
instructionCount int notnull
totalMinor long notnull
createdBy String len=120 notnull
submittedBy String len=120
submittedAt LocalDateTime
approvedBy String len=120
approvedAt LocalDateTime
exportedFileSha256 String len=64
exportedAt LocalDateTime
exportedBy String len=120
completedAt LocalDateTime
notes String len=1000
@created createdAt
@updated updatedAt
@version

@entity SettlementInstruction psp_settlement_instructions zw.co.payport.gateway.psp.settlement
@doc One payout to one merchant. Funds are earmarked in the ledger (SETTLEMENT_PAYABLE) the moment it is created.
@index idx_sinst_batch batch_id
@index idx_sinst_merchant merchant_id,created_at
@index idx_sinst_status status
instructionRef String len=40 notnull unique
batchId Long notnull
merchantId Long notnull
currency String len=3 notnull
amountMinor long notnull
payoutAccountRef String len=40 notnull
method String len=20 notnull
rail String len=20
destinationName String len=200 notnull
destinationMasked String len=40 notnull
bankName String len=120
status String len=20 notnull
reservationEntryRef String len=40
paidEntryRef String len=40
reversalEntryRef String len=40
railReference String len=150
payoutReference String len=150
proofSha256 String len=64
proofFileName String len=255
proofStoragePath String len=500
bankChargeMinor long notnull
failureReason String len=1000
sentAt LocalDateTime
paidAt LocalDateTime
failedAt LocalDateTime
confirmedBy String len=120
@created createdAt
@updated updatedAt
@version
