-- Owner requests 2026-10-24.
--  1. Every agent and merchant has a six-digit code customers type (cash-out at an agent, pay a
--     merchant). Codes are system generated and unique across agents and merchants together
--     (party_codes is the single allocation table; its primary key makes a double allocation impossible).
--  2. Merchants record their address at onboarding (agents already record location and phone).
--  3. Limits, charges and tax per party (WALLET customers, AGENT, MERCHANT), product, currency and channel.
--  4. Cards linked to wallets (virtual and physical, VISA or ZimSwitch), managed through the issuer's API.

CREATE TABLE party_codes (
    code        CHAR(6)       NOT NULL PRIMARY KEY,
    party_type  VARCHAR(10)   NOT NULL,
    party_id    BIGINT        NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    CONSTRAINT uq_party_code_owner UNIQUE (party_type, party_id),
    CONSTRAINT chk_party_code_type CHECK (party_type IN ('AGENT', 'MERCHANT')),
    CONSTRAINT chk_party_code_digits CHECK (code REGEXP '^[1-9][0-9]{5}$')
);

ALTER TABLE merchants
    ADD COLUMN merchant_code CHAR(6) NULL,
    ADD COLUMN address VARCHAR(300) NULL,
    ADD CONSTRAINT uq_merchant_code UNIQUE (merchant_code);

ALTER TABLE agents
    ADD COLUMN code CHAR(6) NULL AFTER agent_code,
    ADD CONSTRAINT uq_agent_code6 UNIQUE (code);

CREATE TABLE party_rules (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    party_type          VARCHAR(10)   NOT NULL,
    product             VARCHAR(40)   NOT NULL DEFAULT 'ALL',
    currency            CHAR(3)       NOT NULL,
    channel             VARCHAR(20)   NOT NULL DEFAULT 'ALL',
    min_amount_minor    BIGINT        NULL,
    max_amount_minor    BIGINT        NULL,
    daily_max_minor     BIGINT        NULL,
    weekly_max_minor    BIGINT        NULL,
    monthly_max_minor   BIGINT        NULL,
    daily_max_count     INT           NULL,
    weekly_max_count    INT           NULL,
    monthly_max_count   INT           NULL,
    charge_flat_minor   BIGINT        NOT NULL DEFAULT 0,
    charge_percent_bps  INT           NOT NULL DEFAULT 0,
    charge_min_minor    BIGINT        NULL,
    charge_max_minor    BIGINT        NULL,
    tax_chargeable      BOOLEAN       NOT NULL DEFAULT FALSE,
    tax_percent_bps     INT           NOT NULL DEFAULT 0,
    tax_base            VARCHAR(10)   NOT NULL DEFAULT 'CHARGE',
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_by          VARCHAR(160)  NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    CONSTRAINT uq_party_rule_scope UNIQUE (party_type, product, currency, channel),
    CONSTRAINT chk_party_rule_type CHECK (party_type IN ('WALLET', 'AGENT', 'MERCHANT')),
    CONSTRAINT chk_party_rule_tax_base CHECK (tax_base IN ('CHARGE', 'AMOUNT')),
    CONSTRAINT chk_party_rule_amounts CHECK (
        (min_amount_minor IS NULL OR min_amount_minor >= 0) AND (max_amount_minor IS NULL OR max_amount_minor > 0)
        AND (min_amount_minor IS NULL OR max_amount_minor IS NULL OR min_amount_minor <= max_amount_minor)
        AND charge_flat_minor >= 0 AND charge_percent_bps BETWEEN 0 AND 10000 AND tax_percent_bps BETWEEN 0 AND 10000
        AND (charge_min_minor IS NULL OR charge_max_minor IS NULL OR charge_min_minor <= charge_max_minor))
);

CREATE TABLE wallet_cards (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    card_ref        VARCHAR(20)   NOT NULL,
    wallet_id       BIGINT        NOT NULL,
    scheme          VARCHAR(10)   NOT NULL,
    form            VARCHAR(10)   NOT NULL,
    status          VARCHAR(12)   NOT NULL DEFAULT 'REQUESTED',
    masked_pan      VARCHAR(25)   NULL,
    last4           CHAR(4)       NULL,
    expiry          VARCHAR(7)    NULL,
    name_on_card    VARCHAR(60)   NULL,
    issuer_ref      VARCHAR(80)   NULL,
    delivery_address VARCHAR(300) NULL,
    status_note     VARCHAR(300)  NULL,
    created_by      VARCHAR(160)  NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    CONSTRAINT uq_card_ref UNIQUE (card_ref),
    CONSTRAINT fk_card_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT chk_card_scheme CHECK (scheme IN ('VISA', 'ZIMSWITCH')),
    CONSTRAINT chk_card_form CHECK (form IN ('VIRTUAL', 'PHYSICAL')),
    CONSTRAINT chk_card_status CHECK (status IN ('REQUESTED', 'ACTIVE', 'FROZEN', 'BLOCKED', 'CANCELLED', 'REFUSED'))
);

-- What each party has done, for the daily/weekly/monthly value and count limits of wallet, agent and
-- merchant operations (product payments are counted from the transactions table itself).
CREATE TABLE party_usage (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    party_type    VARCHAR(10)   NOT NULL,
    party_key     VARCHAR(80)   NOT NULL,
    product       VARCHAR(40)   NOT NULL,
    currency      CHAR(3)       NOT NULL,
    channel       VARCHAR(20)   NOT NULL,
    amount_minor  BIGINT        NOT NULL,
    charge_minor  BIGINT        NOT NULL DEFAULT 0,
    tax_minor     BIGINT        NOT NULL DEFAULT 0,
    op_ref        VARCHAR(120)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    CONSTRAINT uq_party_usage_op UNIQUE (party_type, op_ref),
    INDEX idx_party_usage_scope (party_type, party_key, currency, created_at)
);

-- A wallet payment carries its charge and tax (taken with the payment, credited to fee revenue and tax).
ALTER TABLE wallet_payment_authorizations
    ADD COLUMN charge_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tax_minor BIGINT NOT NULL DEFAULT 0;

-- The channel a merchant payment came through (pay by merchant code from the app, web or USSD).
ALTER TABLE checkout_sessions ADD COLUMN channel VARCHAR(10) NULL;
