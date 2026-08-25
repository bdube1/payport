-- V12: Beneficiaries — was being stored only in local device storage on
-- mobile (AsyncStorage), which is why beneficiaries created on the mobile
-- app never appeared on the web app for the same logged-in user. This
-- table centralises it so both clients read/write the same data.

CREATE TABLE beneficiaries (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_phone      VARCHAR(20)     NOT NULL,
    type            VARCHAR(10)     NOT NULL,      -- PHONE | BILLS
    name            VARCHAR(100)    NOT NULL,
    phone           VARCHAR(20),                    -- for type = PHONE
    network_name    VARCHAR(30),                    -- for type = PHONE (e.g. Econet)
    network_color   VARCHAR(10),                    -- for type = PHONE, UI hint
    biller_code     VARCHAR(30),                    -- for type = BILLS
    biller_name     VARCHAR(100),                   -- for type = BILLS
    account_ref     VARCHAR(100),                   -- for type = BILLS
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_beneficiaries_user_phone ON beneficiaries(user_phone);
