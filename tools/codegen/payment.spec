@entity CallbackInboxEntry psp_callback_inbox zw.co.payport.gateway.psp.payment
@doc Every provider callback is stored here BEFORE processing, so none is ever lost even if processing fails.
@index idx_cbin_status status,next_attempt_at
@index idx_cbin_ref payport_reference
@index uq_cbin_dedupe provider,body_hash unique
provider String len=30 notnull
bodyHash String len=64 notnull
rawBody String text notnull
headers String text
remoteIp String len=64
receivedAt LocalDateTime notnull
duplicateCount int notnull
status String len=20 notnull
signatureValid Boolean
verification String len=30
payportReference String len=60
providerReference String len=200
parsedStatus String len=20
parsedAmountMinor Long
attempts int notnull
nextAttemptAt LocalDateTime
lastError String len=2000
processedAt LocalDateTime

@entity PaymentCheck psp_payment_checks zw.co.payport.gateway.psp.payment
@doc Polling state for a payment whose outcome is not yet known. Drives back-off and escalation; shared across app instances.
@index idx_pcheck_due status,next_check_at
reference String len=60 notnull unique
provider String len=30
status String len=20 notnull
attempts int notnull
firstSeenAt LocalDateTime notnull
lastCheckedAt LocalDateTime
nextCheckAt LocalDateTime notnull
lastResult String len=500
investigationOpened boolean notnull
resolvedOutcome String len=20
resolvedBy String len=120
resolvedAt LocalDateTime

@entity RoutingRule psp_routing_rules zw.co.payport.gateway.psp.routing
@doc Smart-routing configuration: which provider can serve an instrument/currency/amount band, with priority, weight and cost.
@index idx_route_lookup instrument,currency,enabled
instrument String len=30 notnull
currency String len=3 notnull
provider String len=30 notnull
priority int notnull
weight int notnull
minAmountMinor long notnull
maxAmountMinor long notnull
costBps int notnull
costFixedMinor long notnull
networkCode String len=20
enabled boolean notnull
notes String len=300
@created createdAt
@updated updatedAt
