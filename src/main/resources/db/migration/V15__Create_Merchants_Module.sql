-- Merchants Module, Phase 0: onboarding + auth + API key foundations.
-- Entirely new, self-contained tables -- no existing table is altered here,
-- so this cannot break anything already in production.

CREATE TABLE merchants (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_name VARCHAR(255) NOT NULL,
    registration_number VARCHAR(100) NOT NULL,
    business_category VARCHAR(100),
    contact_email VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | ACTIVE | SUSPENDED | CLOSED | REJECTED
    rejection_reason VARCHAR(500),

    -- Payout destination -- required before LIVE keys unlock, not at signup.
    payout_account_type VARCHAR(20),   -- BANK | ECOCASH | ONEMONEY
    payout_account_details VARCHAR(500), -- encrypted at rest, see EncryptionService

    commission_rate_bps INT,           -- basis points; null = use platform default
    default_currency VARCHAR(3) NOT NULL DEFAULT 'USD',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at DATETIME NULL,
    approved_by BIGINT NULL,           -- FK to users.id (the staff member who approved)

    CONSTRAINT chk_merchant_status CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','CLOSED','REJECTED'))
);

CREATE UNIQUE INDEX idx_merchants_registration_number ON merchants(registration_number);
CREATE INDEX idx_merchants_status ON merchants(status);

-- Mirrors the staff User security fields already hardened this year
-- (failed_login_attempts, locked, password expiry) rather than inventing a
-- new auth pattern -- same policy, same lockout behaviour, different table.
CREATE TABLE merchant_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'OWNER',  -- OWNER | DEVELOPER | FINANCE
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DEACTIVATED

    failed_login_attempts INT NOT NULL DEFAULT 0,
    account_locked BOOLEAN NOT NULL DEFAULT FALSE,
    password_set_at DATETIME NULL,
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    reset_token VARCHAR(255) NULL,
    reset_token_expires_at DATETIME NULL,

    last_login_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_merchant_users_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT chk_merchant_user_role CHECK (role IN ('OWNER','DEVELOPER','FINANCE'))
);

CREATE UNIQUE INDEX idx_merchant_users_email ON merchant_users(email);

CREATE TABLE api_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    environment VARCHAR(10) NOT NULL,   -- TEST | LIVE
    public_key VARCHAR(64) NOT NULL,
    secret_key_hash VARCHAR(100) NOT NULL,  -- bcrypt hash -- the raw secret is shown once, never stored
    label VARCHAR(100),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at DATETIME NULL,
    revoked_at DATETIME NULL,

    CONSTRAINT fk_api_keys_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT chk_api_key_env CHECK (environment IN ('TEST','LIVE'))
);

CREATE UNIQUE INDEX idx_api_keys_public_key ON api_keys(public_key);
