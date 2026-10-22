-- Only needed if the Phase 0.5 code was rolled back (livemode made NULLable) and is now being
-- re-deployed. Classifies sessions created while the old code ran, with the same rules as V32,
-- then restores NOT NULL. Safe to run more than once.
UPDATE checkout_sessions cs JOIN api_keys k ON k.id = cs.api_key_id
   SET cs.livemode = (k.environment = 'LIVE') WHERE cs.livemode IS NULL;
UPDATE checkout_sessions cs JOIN transactions t ON t.reference = cs.transaction_reference
   SET cs.livemode = FALSE WHERE cs.livemode IS NULL AND t.provider_reference LIKE 'TEST\_SIM\_%';
UPDATE checkout_sessions SET livemode = TRUE WHERE livemode IS NULL;
ALTER TABLE checkout_sessions MODIFY livemode BOOLEAN NOT NULL;
