# ── Event bus: transactional outbox ────────────────────────────────────────
@entity OutboxEvent psp_outbox_events zw.co.payport.gateway.psp.platform.outbox
@doc An event written in the same DB transaction as the state change that caused it.
@doc Dispatched at-least-once to in-process handlers (and optionally an external broker).
@index idx_outbox_dispatch status,next_attempt_at
@index idx_outbox_aggregate aggregate_type,aggregate_id
eventId String len=40 notnull unique updatable=false
eventType String len=80 notnull updatable=false
aggregateType String len=60 notnull updatable=false
aggregateId String len=120 notnull updatable=false
payload String text notnull updatable=false
status String len=20 notnull default="NEW"
attempts int notnull
nextAttemptAt LocalDateTime notnull
lockedBy String len=80
lockedUntil LocalDateTime
lastError String len=2000
publishedAt LocalDateTime
@created createdAt

@entity OutboxDelivery psp_outbox_deliveries zw.co.payport.gateway.psp.platform.outbox
@doc Records that one handler has processed one event, so retries only re-run handlers that failed.
@index uq_outbox_delivery event_id,handler unique
eventId String len=40 notnull
handler String len=120 notnull
@created deliveredAt

# ── Idempotency keys for client-initiated writes ──────────────────────────
@entity IdempotencyRecord psp_idempotency_keys zw.co.payport.gateway.psp.platform.idempotency
@index uq_idem_scope_key scope,idem_key unique
@index idx_idem_expiry expires_at
scope String len=120 notnull
idemKey String col=idem_key len=120 notnull
requestHash String len=64 notnull
status String len=20 notnull
responseStatus Integer
responseBody String text
resourceRef String len=120
expiresAt LocalDateTime notnull
@created createdAt
@updated updatedAt

# ── Maker-checker approvals ───────────────────────────────────────────────
@entity ApprovalRequest psp_approval_requests zw.co.payport.gateway.psp.platform.approval
@doc A sensitive change proposed by one person and executed only after a different person approves it.
@index idx_approval_status status,requested_at
@index idx_approval_target action_type,target_ref
approvalRef String len=40 notnull unique
actionType String len=60 notnull
targetRef String len=120 notnull
payload String text notnull
summary String len=500 notnull
status String len=20 notnull
requiredRole String len=40 notnull
requestedBy String len=120 notnull
requestedAt LocalDateTime notnull
reason String len=1000
decidedBy String len=120
decidedAt LocalDateTime
decisionNote String len=1000
executionResult String len=2000
expiresAt LocalDateTime notnull
@version

# ── Tamper-evident audit trail ────────────────────────────────────────────
@entity AuditEvent psp_audit_events zw.co.payport.gateway.psp.platform.audit
@doc Append-only. UPDATE/DELETE are rejected by DB triggers; rows are hash-sealed by ChainSealService.
@immutable
@index idx_audit_object object_type,object_ref
@index idx_audit_actor actor,created_at
eventRef String len=40 notnull unique
actor String len=120 notnull
actorRoles String len=300
action String len=80 notnull
objectType String len=60 notnull
objectRef String len=120 notnull
details String text
ipAddress String len=64
requestId String len=80
contentHash String len=64 notnull
@created createdAt

@entity ChainSeal psp_chain_seals zw.co.payport.gateway.psp.platform.audit
@doc One link in a hash chain over an append-only table (LEDGER or AUDIT).
@immutable
@index uq_seal_chain_to chain_name,to_id unique
chainName String len=20 notnull
fromId Long notnull
toId Long notnull
rowCount int notnull
sealHash String len=64 notnull
prevSealHash String len=64 notnull
@created createdAt
