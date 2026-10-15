@entity PartnerClient psp_partner_clients zw.co.payport.gateway.psp.channel
@doc An API partner (aggregator, bank, fintech, large merchant) authenticated by HMAC request signing and optionally mTLS.
@index idx_partner_merchant merchant_id
keyId String len=40 notnull unique
name String len=200 notnull
merchantId Long
secret String len=600 notnull encrypted
scopes String len=500 notnull
allowedIps String len=1000
mtlsCertSha256 String len=64
status String len=20 notnull
rateLimitPerMinute int notnull
createdBy String len=120 notnull
lastUsedAt LocalDateTime
@created createdAt
@updated updatedAt

@entity RequestNonce psp_request_nonces zw.co.payport.gateway.psp.channel
@doc Replay protection for signed requests: a (client, nonce) pair is accepted once. Purged after 15 minutes.
@index uq_nonce client_id,nonce unique
@index idx_nonce_created created_at
clientId String len=40 notnull
nonce String len=80 notnull
@created createdAt

@entity Terminal psp_terminals zw.co.payport.gateway.psp.channel
@doc A POS / mPOS / SoftPOS terminal assigned to a merchant. Authenticates with its own HMAC secret.
@index idx_terminal_merchant merchant_id
terminalId String len=16 notnull unique
merchantId Long notnull
terminalType String len=10 notnull
serialNumber String len=80
model String len=80
locationLabel String len=200
secret String len=600 notnull encrypted
status String len=20 notnull
lastSeenAt LocalDateTime
appVersion String len=40
assignedBy String len=120 notnull
@created createdAt
@updated updatedAt

@entity ChatSession psp_chat_sessions zw.co.payport.gateway.psp.channel
@doc Conversation state for the WhatsApp bot, keyed by WhatsApp number.
@index uq_chat_channel_user channel,user_ref unique
channel String len=20 notnull
userRef String len=40 notnull
state String len=40 notnull
data String text
expiresAt LocalDateTime notnull
@updated updatedAt
