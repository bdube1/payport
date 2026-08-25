-- V4__Add_ZimSwitch.sql
-- ZimSwitch is already seeded in V1 as a payment_method row.
-- This migration just adds it to the provider_health table
-- so the health scheduler can track its status.

INSERT INTO provider_health (provider_code, provider_type, status, manual_override, updated_at)
SELECT 'ZIMSWITCH', 'PAYMENT', 'DOWN', FALSE, NOW()
    WHERE NOT EXISTS (
    SELECT 1 FROM provider_health WHERE provider_code = 'ZIMSWITCH'
);