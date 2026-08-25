-- Flyway migration V9
-- Description: Create bundle_products table for storing provider bundle catalogues
-- Author: PayPort Team
-- Date: 2026-03-17

CREATE TABLE IF NOT EXISTS bundle_products (
                                               id BIGINT PRIMARY KEY AUTO_INCREMENT,

    -- Provider identification
                                               provider_code VARCHAR(20) NOT NULL,
    provider_name VARCHAR(50),

    -- Bundle identification
    bundle_code VARCHAR(50) NOT NULL,
    bundle_name VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    category VARCHAR(50),

    -- Pricing (ALL monetary values stored in cents - NEVER use double/float)
    price_usd_cents INT,
    price_zwg_cents INT,

    -- Bundle details
    data_volume_mb INT,
    validity_days INT,
    validity_hours INT,
    includes_sms INT,
    includes_voice_minutes INT,
    peak_data_mb INT,
    offpeak_data_mb INT,

    -- Display and marketing
    is_active BOOLEAN DEFAULT TRUE,
    display_order INT DEFAULT 0,
    popular_tag VARCHAR(50),

    -- Features JSON for custom bundle attributes
    features_json TEXT,

    -- Source tracking (for Excel uploads)
    source_file VARCHAR(255),
    source_version VARCHAR(50),

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    effective_from TIMESTAMP NULL,
    effective_to TIMESTAMP NULL,

    -- Constraints
    CONSTRAINT uk_bundle_provider_code UNIQUE (provider_code, bundle_code),
    INDEX idx_bundle_provider_active (provider_code, is_active),
    INDEX idx_bundle_category (provider_code, category, is_active),
    INDEX idx_bundle_validity (provider_code, validity_days, validity_hours),
    INDEX idx_bundle_price (provider_code, price_usd_cents, price_zwg_cents)

    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- REMOVED: The COMMENT line that was causing the error
-- H2 doesn't support ALTER TABLE ... COMMENT = '...'