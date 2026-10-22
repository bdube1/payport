-- =====================================================================
-- Phase 0.5 containment (R1, R3).
--
-- R1: a checkout session created without a merchant API key (payment links,
-- dashboard-created checkouts) was treated as TEST mode: the payment was
-- simulated, the session marked PAID, and it became settleable. Livemode is
-- now an explicit, stored attribute of every session, decided when the session
-- is created, and settlement only ever includes livemode sessions.
--
-- Backfill rule: classify by what actually happened, not by guesswork.
--   1. Sessions created with an API key -> that key's environment.
--   2. Sessions whose payment was simulated (provider reference TEST_SIM_...)
--      -> FALSE. These never moved real money.
--   3. Everything else -> TRUE (never paid, or paid through a real provider).
-- scripts/phase0.5/r1-exposure-report.sql lists what this changed, for review.
--
-- R3: public transaction lookups are now gated by a per-transaction access
-- token issued at initiation. Only its SHA-256 hash is stored.
--
-- Reversible: see the DOWN notes at the end.
-- =====================================================================

ALTER TABLE checkout_sessions ADD COLUMN livemode BOOLEAN NULL;

UPDATE checkout_sessions cs
JOIN api_keys k ON k.id = cs.api_key_id
SET cs.livemode = (k.environment = 'LIVE');

UPDATE checkout_sessions cs
JOIN transactions t ON t.reference = cs.transaction_reference
SET cs.livemode = FALSE
WHERE cs.livemode IS NULL
  AND t.provider_reference LIKE 'TEST\_SIM\_%';

UPDATE checkout_sessions SET livemode = TRUE WHERE livemode IS NULL;

ALTER TABLE checkout_sessions MODIFY livemode BOOLEAN NOT NULL;
CREATE INDEX idx_checkout_sessions_livemode ON checkout_sessions (merchant_id, livemode, status);

ALTER TABLE transactions ADD COLUMN access_token_hash VARCHAR(64) NULL;

-- DOWN (manual, only if this release is rolled back):
--   ALTER TABLE transactions DROP COLUMN access_token_hash;
--   DROP INDEX idx_checkout_sessions_livemode ON checkout_sessions;
--   ALTER TABLE checkout_sessions DROP COLUMN livemode;
--   DELETE FROM flyway_schema_history WHERE version = '32';
-- Code-only rollback: FIRST run  ALTER TABLE checkout_sessions MODIFY livemode BOOLEAN NULL;
-- The previous code never sets livemode, so the NOT NULL column would reject every new session.
-- (Editing this comment before V32 is applied anywhere does not change the migration's SQL; Flyway's
-- checksum covers the whole file, so do not edit it after it has been applied.)
