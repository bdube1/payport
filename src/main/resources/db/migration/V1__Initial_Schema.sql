-- ================================================================
-- PayPort Gateway - Database Schema V1
-- All monetary amounts stored as BIGINT (cents) - NEVER FLOAT
-- All timestamps stored as UTC
-- ================================================================

-- ================================================================
-- PRODUCT TYPES CATALOGUE
-- ================================================================
CREATE TABLE product_types (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(50)     NOT NULL UNIQUE,
    name                VARCHAR(100)    NOT NULL,
    category            VARCHAR(30)     NOT NULL,
    provider_code       VARCHAR(50)     NOT NULL,
    is_active           BOOLEAN         NOT NULL DEFAULT FALSE,
    min_amount_cents    BIGINT          NOT NULL DEFAULT 01,
    max_amount_cents    BIGINT          NOT NULL DEFAULT 2000000,
    currency            VARCHAR(3)      NOT NULL DEFAULT 'USD',
    fulfilment_config   TEXT,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================================================================
-- PAYMENT METHODS CATALOGUE
-- ================================================================
CREATE TABLE payment_methods (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(50)     NOT NULL UNIQUE,
    name                VARCHAR(100)    NOT NULL,
    is_active           BOOLEAN         NOT NULL DEFAULT FALSE,
    gateway_config      TEXT,
    display_order       INT             NOT NULL DEFAULT 0,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================================================================
-- SERVICE PROVIDERS CATALOGUE
-- ================================================================
CREATE TABLE service_providers (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(50)     NOT NULL UNIQUE,
    name                VARCHAR(100)    NOT NULL,
    provider_type       VARCHAR(30)     NOT NULL,
    is_active           BOOLEAN         NOT NULL DEFAULT FALSE,
    api_config          TEXT,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================================================================
-- NETWORK PREFIXES
-- ================================================================
CREATE TABLE network_prefixes (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    prefix              VARCHAR(10)     NOT NULL UNIQUE,
    network_code        VARCHAR(20)     NOT NULL,
    network_name        VARCHAR(50)     NOT NULL,
    country_code        VARCHAR(2)      NOT NULL DEFAULT 'ZW'
);

-- ================================================================
-- USERS TABLE (consumers + ops staff)
-- ================================================================
CREATE TABLE users (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name           VARCHAR(200)    NOT NULL,
    phone               VARCHAR(20)     NOT NULL UNIQUE,
    pin_hash            VARCHAR(100)    NOT NULL,
    role                VARCHAR(20)     NOT NULL DEFAULT 'CUSTOMER',
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    is_verified         BOOLEAN         NOT NULL DEFAULT FALSE,
    verification_code   VARCHAR(10),
    last_login_at       TIMESTAMP,
    failed_pin_attempts INT             NOT NULL DEFAULT 0,
    locked_until        TIMESTAMP,
    registered_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_role  ON users(role);

-- ================================================================
-- CORE TRANSACTIONS TABLE
-- ================================================================
CREATE TABLE transactions (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    reference                       VARCHAR(24)     NOT NULL UNIQUE,
    status                          VARCHAR(30)     NOT NULL DEFAULT 'INITIATED',
    channel                         VARCHAR(10)     NOT NULL,

    -- Product & payment
    product_type_id                 BIGINT          NOT NULL REFERENCES product_types(id),
    payment_method_id               BIGINT,

    -- Parties
    user_id                         BIGINT          REFERENCES users(id),
    customer_phone                  VARCHAR(20),
    customer_name                   VARCHAR(200),
    customer_email                  VARCHAR(200),
    recipient_identifier            VARCHAR(100)    NOT NULL,
    recipient_network               VARCHAR(20),
    recipient_name                  VARCHAR(200),

    -- Money (ALWAYS CENTS)
    amount_cents                    BIGINT          NOT NULL,
    currency                        VARCHAR(3)      NOT NULL DEFAULT 'USD',

    -- Product snapshot
    product_name                    VARCHAR(200),
    payment_method_name             VARCHAR(100),

    -- Payment provider details
    payment_provider_code           VARCHAR(50),
    provider_reference              VARCHAR(200),
    payment_correlator              VARCHAR(200),
    payment_provider_tx_id          VARCHAR(200),
    payment_provider_status         VARCHAR(100),
    payment_provider_message        VARCHAR(500),
    payment_provider_raw_response   TEXT,
    payment_confirmed_amount_cents  BIGINT,
    payment_confirmed_currency      VARCHAR(3),

    -- Fulfilment provider details
    fulfilment_provider_code        VARCHAR(50),
    fulfilment_reference            VARCHAR(200),
    fulfilment_data                 TEXT,
    fulfilment_provider_raw_response TEXT,
    fulfilment_provider_status      VARCHAR(100),

    -- Meta & tracking
    ip_address                      VARCHAR(45),
    user_agent                      VARCHAR(500),
    device_type                     VARCHAR(50),
    session_id                      VARCHAR(100),
    retry_count                     INT             NOT NULL DEFAULT 0,
    failure_reason                  VARCHAR(2000),
    notes                           TEXT,
    ops_assigned_to                 VARCHAR(100),

    -- Timestamps
    initiated_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payment_submitted_at            TIMESTAMP,
    payment_confirmed_at            TIMESTAMP,
    fulfilment_started_at           TIMESTAMP,
    completed_at                    TIMESTAMP,
    failed_at                       TIMESTAMP,
    created_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_tx_reference   ON transactions(reference);
CREATE INDEX idx_tx_status      ON transactions(status, created_at);
CREATE INDEX idx_tx_customer    ON transactions(customer_phone);
CREATE INDEX idx_tx_recipient   ON transactions(recipient_identifier);
CREATE INDEX idx_tx_created     ON transactions(created_at);
CREATE INDEX idx_tx_user        ON transactions(user_id);

-- ================================================================
-- TRANSACTION AUDIT LOG
-- ================================================================
CREATE TABLE transaction_audit_log (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_reference   VARCHAR(24)     NOT NULL,
    from_status             VARCHAR(30),
    to_status               VARCHAR(30)     NOT NULL,
    actor                   VARCHAR(100)    NOT NULL,
    notes                   TEXT,
    payload_snapshot        TEXT,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_reference ON transaction_audit_log(transaction_reference);
CREATE INDEX idx_audit_created   ON transaction_audit_log(created_at);

-- ================================================================
-- PROVIDER HEALTH
-- ================================================================
CREATE TABLE provider_health (
    provider_code       VARCHAR(50)     NOT NULL PRIMARY KEY,
    provider_type       VARCHAR(20)     NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'OPERATIONAL',
    success_rate_10m    DECIMAL(5,2),
    last_checked_at     TIMESTAMP,
    manual_override     BOOLEAN         NOT NULL DEFAULT FALSE,
    override_reason     VARCHAR(200),
    override_by         VARCHAR(100),
    override_at         TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================================================================
-- NOTIFICATION LOG
-- ================================================================
CREATE TABLE notification_log (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_reference   VARCHAR(24),
    recipient_phone         VARCHAR(20)     NOT NULL,
    message_type            VARCHAR(50)     NOT NULL,
    message_body            TEXT            NOT NULL,
    provider                VARCHAR(30)     NOT NULL DEFAULT 'AFRICAS_TALKING',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    provider_message_id     VARCHAR(100),
    error_message           VARCHAR(500),
    sent_at                 TIMESTAMP,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ================================================================
-- SEED - Payment Methods
-- ================================================================
INSERT INTO payment_methods (code, name, is_active, display_order) VALUES
('ECOCASH',   'EcoCash',           TRUE,  1),
('ONEMONEY',  'OneMoney',          TRUE,  2),
('TELECASH',  'Telecash',          TRUE,  3),
('OMARI',     'Omari',             TRUE, 4),
('INNBUCKS',  'InnBucks',          TRUE, 5),
('VISA_MC',   'Visa / Mastercard', TRUE, 6),
('ZIMSWITCH', 'ZimSwitch',         TRUE, 7);

-- ================================================================
-- SEED - Service Providers
-- ================================================================
INSERT INTO service_providers (code, name, provider_type, is_active) VALUES
('ECONET',    'Econet Wireless',   'FULFILMENT', TRUE),
('NETONE',    'NetOne',            'FULFILMENT', TRUE),
('TELECEL',   'Telecel Zimbabwe',  'FULFILMENT', TRUE),
('ZESA',      'ZESA/ZETDC',        'FULFILMENT', TRUE),
('DSTV',      'MultiChoice DStv',  'FULFILMENT', TRUE),
('TELONE',    'TelOne',            'FULFILMENT', TRUE),
('ECOCASH',   'EcoCash',           'PAYMENT',    TRUE),
('ONEMONEY',  'OneMoney',          'PAYMENT',    TRUE),
('TELECASH',  'Telecash',          'PAYMENT',    TRUE);

-- ================================================================
-- SEED - Product Types
-- ================================================================
INSERT INTO product_types (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents) VALUES
('AIRTIME_ECONET',   'Econet Airtime',      'AIRTIME', 'ECONET',  TRUE,  50,    20000),
('AIRTIME_NETONE',   'NetOne Airtime',      'AIRTIME', 'NETONE',  TRUE,  01,    20000),
('AIRTIME_TELECEL',  'Telecel Airtime',     'AIRTIME', 'TELECEL', TRUE,  50,    20000),
('ZESA_TOKEN',       'ZESA Electricity',    'BILLS',   'ZESA',    TRUE, 100,   500000),
('DSTV_RENEWAL',     'DStv Subscription',   'BILLS',   'DSTV',    TRUE, 1000,  30000),
('TELONE_BILL',      'TelOne Bill Payment', 'BILLS',   'TELONE',  TRUE, 100,   100000);

-- ================================================================
-- SEED - Network Prefixes (Zimbabwe)
-- ================================================================
INSERT INTO network_prefixes (prefix, network_code, network_name) VALUES
('0771', 'ECONET',  'Econet Wireless'),
('0772', 'ECONET',  'Econet Wireless'),
('0773', 'ECONET',  'Econet Wireless'),
('0774', 'ECONET',  'Econet Wireless'),
('0775', 'ECONET',  'Econet Wireless'),
('0776', 'ECONET',  'Econet Wireless'),
('0777', 'ECONET',  'Econet Wireless'),
('0778', 'ECONET',  'Econet Wireless'),
('0713', 'TELECEL', 'Telecel Zimbabwe'),
('0714', 'TELECEL', 'Telecel Zimbabwe'),
('0715', 'TELECEL', 'Telecel Zimbabwe'),
('0716', 'TELECEL', 'Telecel Zimbabwe'),
('0717', 'TELECEL', 'Telecel Zimbabwe'),
('0719', 'NETONE', 'NetOne'),
('0711', 'NETONE',  'NetOne'),
('0712', 'NETONE',  'NetOne');

-- ================================================================
-- SEED - Provider Health
-- ================================================================
INSERT INTO provider_health (provider_code, provider_type, status) VALUES
('ECOCASH',  'PAYMENT',    'OPERATIONAL'),
('ONEMONEY', 'PAYMENT',    'OPERATIONAL'),
('TELECASH', 'PAYMENT',    'OPERATIONAL'),
('ECONET',   'FULFILMENT', 'OPERATIONAL'),
('NETONE',   'FULFILMENT', 'OPERATIONAL'),
('TELECEL',  'FULFILMENT', 'OPERATIONAL');

-- ================================================================
-- SEED - Default Ops Users
-- PIN 1234 → BCrypt hash
-- ================================================================
INSERT INTO users (full_name, phone, pin_hash, role, is_active, is_verified) VALUES
('PayPort Admin', '+263771000000', '$2y$10$wkGXVRhG1QPO.WxOw5X6OOVioK3xo8ajS5Tf.e61Nn.UATvG5v20q', 'ADMIN', TRUE, TRUE),
('Ops Manager', '+263771000001', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE2ceVFsSkKiZS8Ay', 'OPS', TRUE, TRUE),
('Brighton Dube', '+263772699555', '$2a$10$JMEEqKTzpxx9HKc0q4E01OWReU/XsYD/NjK2c.lZBLBp46ingACNq', 'CUSTOMER', TRUE, TRUE);
