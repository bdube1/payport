-- Provider status shows only what was checked (owner, 2026-10-25). The rows seeded as OPERATIONAL were never
-- verified by anything; they become NOT_CHECKED until a real check or an operator says otherwise.
ALTER TABLE provider_health ALTER status SET DEFAULT 'NOT_CHECKED';
UPDATE provider_health SET status = 'NOT_CHECKED', detail = 'No automatic check'
 WHERE status = 'OPERATIONAL' AND manual_override = FALSE;
