-- V13: Single-device login for customers.
--
-- Nullable, no default enforcement — existing users are completely
-- unaffected until they log in again from a client that sends a deviceId.
-- Rollout is gradual and backward-compatible: old app versions that don't
-- send deviceId keep working exactly as before, un-restricted, until
-- updated.
ALTER TABLE users ADD COLUMN active_device_id VARCHAR(255) NULL;
