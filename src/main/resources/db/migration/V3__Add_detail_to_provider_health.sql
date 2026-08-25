-- ================================================================
-- Add detail column to provider_health for storing status messages
-- ================================================================

ALTER TABLE provider_health ADD COLUMN detail VARCHAR(500);

-- Update existing records with default detail based on status
UPDATE provider_health SET detail = 'Initial operational status' WHERE status = 'OPERATIONAL' AND detail IS NULL;