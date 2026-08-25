-- Merchants Module: payment method tracking, commission calculation on
-- each collection, and role-based team management enforcement.

ALTER TABLE checkout_sessions
    ADD COLUMN payment_method_code VARCHAR(20) NULL,
    ADD COLUMN commission_bps_applied INT NULL,        -- the actual rate used, frozen at payment time -- a later
                                                          -- change to the merchant's rate must never retroactively
                                                          -- alter a past collection's commission
    ADD COLUMN commission_amount_cents BIGINT NULL,
    ADD COLUMN net_amount_cents BIGINT NULL;

CREATE INDEX idx_checkout_sessions_payment_method ON checkout_sessions(payment_method_code);

-- Platform-wide default commission rate, used whenever a merchant has no
-- specific override (merchants.commission_rate_bps IS NULL). A single row,
-- editable by ops -- not hardcoded in application.properties, so it can
-- change without a redeploy.
CREATE TABLE platform_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_settings (setting_key, setting_value)
VALUES ('default_commission_bps', '250');
-- 250 basis points = 2.5%. This is a placeholder, not a business
-- decision -- flagged as an open question in the original design doc.
-- Change it via the ops portal once a real rate is confirmed.
