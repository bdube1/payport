@entity CustomerProfile psp_customer_profiles zw.co.payport.gateway.psp.kyc
@doc A wallet customer and their KYC tier. National ID encrypted, with a blind index for duplicate detection.
@index idx_cust_tier kyc_tier
customerRef String len=40 notnull unique
msisdn String len=20 notnull unique
userId Long
firstName String len=100
lastName String len=100
dateOfBirth LocalDate
nationalId String len=600 encrypted
nationalIdIndex String len=64 unique
kycTier String len=10 notnull
idVerificationResult String len=20
idVerificationRef String len=120
idVerifiedAt LocalDateTime
proofOfResidenceRef String len=200
pep boolean notnull
sanctionsStatus String len=20 notnull
riskRating String len=10 notnull
status String len=20 notnull
statusReason String len=500
@created createdAt
@updated updatedAt
@version

@entity KycTierLimit psp_kyc_tier_limits zw.co.payport.gateway.psp.kyc
@doc Wallet limits per KYC tier and currency. Set these to your RBZ licence conditions.
@index uq_tier_ccy kyc_tier,currency unique
kycTier String len=10 notnull
currency String len=3 notnull
maxBalanceMinor long notnull
maxTxnMinor long notnull
dailyDebitMinor long notnull
monthlyDebitMinor long notnull
dailyCount int notnull
@updated updatedAt

@entity Wallet psp_wallets zw.co.payport.gateway.psp.wallet
@doc An e-money wallet. The balance is NOT stored here: it is the CUSTOMER_WALLET (or AGENT_FLOAT) ledger account.
@index uq_wallet_owner owner_type,owner_ref,currency unique
walletRef String len=40 notnull unique
ownerType String len=20 notnull
ownerRef String len=60 notnull
currency String len=3 notnull
status String len=20 notnull
pinHash String len=200
pinFailures int notnull
lockedUntil LocalDateTime
@created createdAt
@updated updatedAt
@version

@entity WalletTransfer psp_wallet_transfers zw.co.payport.gateway.psp.wallet
@doc Business record of a wallet operation. The money movement itself is the ledger entry in journalEntryRef.
@index idx_wt_from from_ref,created_at
@index idx_wt_to to_ref,created_at
@index idx_wt_type type,created_at
transferRef String len=40 notnull unique
type String len=30 notnull
channel String len=20 notnull
fromRef String len=60
toRef String len=60
merchantId Long
agentId Long
currency String len=3 notnull
amountMinor long notnull
feeMinor long notnull
taxMinor long notnull
commissionMinor long notnull
status String len=20 notnull
riskDecision String len=20
journalEntryRef String len=40
externalRef String len=150
narrative String len=200
initiatedBy String len=120 notnull
failureReason String len=500
@created createdAt
@updated updatedAt

@entity WalletTariff psp_wallet_tariffs zw.co.payport.gateway.psp.wallet
@doc Banded customer fees and agent commissions per operation (P2P, CASH_OUT, CASH_IN, WITHDRAWAL). Tiered bands are typical for Zimbabwean mobile money.
@index idx_tariff_lookup operation,currency,status
operation String len=20 notnull
currency String len=3 notnull
bandMinMinor long notnull
bandMaxMinor long notnull
customerFeeFixedMinor long notnull
customerFeeBps int notnull
agentCommissionFixedMinor long notnull
agentCommissionBps int notnull
status String len=20 notnull
approvedBy String len=120
@created createdAt

@entity Agent psp_agents zw.co.payport.gateway.psp.agent
@doc A cash-in / cash-out agent (or super-agent managing a network of agents).
@index idx_agent_parent parent_agent_id
@index idx_agent_status status
agentRef String len=40 notnull unique
agentCode String len=10 notnull unique
agentType String len=20 notnull
parentAgentId Long
businessName String len=200 notnull
contactName String len=200
msisdn String len=20 notnull unique
nationalIdIndex String len=64
province String len=60
district String len=60
gpsLat String len=20
gpsLng String len=20
status String len=20 notnull
kycVerified boolean notnull
riskRating String len=10 notnull
perTxnLimitMinor long notnull
dailyCashInLimitMinor long notnull
dailyCashOutLimitMinor long notnull
maxFloatMinor long notnull
pinHash String len=200
pinFailures int notnull
lockedUntil LocalDateTime
statusReason String len=500
createdBy String len=120 notnull
approvedBy String len=120
@created createdAt
@updated updatedAt
@version

@entity FxRate psp_fx_rates zw.co.payport.gateway.psp.fx
@doc An FX rate snapshot (RBZ interbank or treasury-entered). Manual rates need four-eyes approval.
@import java.math.BigDecimal
@index idx_fx_pair base_ccy,quote_ccy,status,as_of
rateRef String len=40 notnull unique
baseCcy String len=3 notnull
quoteCcy String len=3 notnull
bid BigDecimal notnull precision=18 scale=8
ask BigDecimal notnull precision=18 scale=8
mid BigDecimal notnull precision=18 scale=8
asOf LocalDateTime notnull
source String len=20 notnull
status String len=20 notnull
createdBy String len=120 notnull
approvedBy String len=120
@created createdAt

@entity FxConversion psp_fx_conversions zw.co.payport.gateway.psp.fx
@doc A customer currency conversion with the exact rate and spread applied.
@import java.math.BigDecimal
@index idx_fxc_customer customer_ref,created_at
conversionRef String len=40 notnull unique
customerRef String len=60 notnull
fromCcy String len=3 notnull
toCcy String len=3 notnull
fromAmountMinor long notnull
toAmountMinor long notnull
midAmountMinor long notnull
customerRate BigDecimal notnull precision=18 scale=8
midRate BigDecimal notnull precision=18 scale=8
rateRef String len=40 notnull
sellEntryRef String len=40
buyEntryRef String len=40
@created createdAt
