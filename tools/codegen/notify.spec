@entity NotificationTemplate psp_notification_templates zw.co.payport.gateway.psp.notify
@doc Message templates per event, channel and language. Placeholders: {{name}}. Edited by ops without a release.
@index uq_ntpl code,channel,locale unique
code String len=60 notnull
channel String len=20 notnull
locale String len=5 notnull
subject String len=200
body String text notnull
active boolean notnull
@updated updatedAt

@entity Notification psp_notifications zw.co.payport.gateway.psp.notify
@doc One outbound message with delivery status and retries.
@index idx_notif_status status,next_attempt_at
@index uq_notif_dedupe dedupe_key unique
notificationRef String len=40 notnull unique
dedupeKey String len=160 notnull
channel String len=20 notnull
recipient String len=200 notnull
templateCode String len=60 notnull
subject String len=200
body String text notnull
status String len=20 notnull
attempts int notnull
nextAttemptAt LocalDateTime notnull
providerRef String len=200
lastError String len=1000
sentAt LocalDateTime
@created createdAt
