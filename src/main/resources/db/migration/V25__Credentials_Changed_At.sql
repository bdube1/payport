-- Fixes a real gap: JWTs are stateless, so resetting a customer's PIN
-- (or deactivating them) previously had no effect on a session token
-- already issued before the change -- it stayed valid until it expired
-- on its own. This timestamp lets the request-level auth filter reject
-- any token issued before the most recent PIN reset, so a reset takes
-- effect on the customer's very next request, not just their next login.

ALTER TABLE users ADD COLUMN credentials_changed_at DATETIME NULL AFTER pin_hash;
