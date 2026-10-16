-- =====================================================================
-- Phase 4: merchant and developer platform.
-- =====================================================================

-- Scoped, expiring, rotatable API keys. Existing keys keep full access ('*').
ALTER TABLE api_keys
    ADD COLUMN scopes VARCHAR(500) NOT NULL DEFAULT '*',
    ADD COLUMN expires_at DATETIME NULL,
    ADD COLUMN rotated_from_id BIGINT NULL;

-- Webhooks v2: encrypted secrets (wider column), signature version, event ids, replays,
-- events that are not about a checkout session (payouts).
ALTER TABLE webhooks
    MODIFY secret VARCHAR(500) NOT NULL,
    ADD COLUMN api_version VARCHAR(10) NOT NULL DEFAULT 'v1',
    ADD COLUMN disabled_reason VARCHAR(300) NULL;
ALTER TABLE webhook_deliveries
    MODIFY checkout_session_id BIGINT NULL,
    ADD COLUMN event_id VARCHAR(64) NULL,
    ADD COLUMN replay_of BIGINT NULL;
CREATE INDEX idx_whd_event ON webhook_deliveries (event_id);

-- KYB case management. Approving a merchant takes two different staff members:
-- one recommends (with the checklist complete), another decides.
CREATE TABLE kyb_cases (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    merchant_id      BIGINT        NOT NULL,
    status           VARCHAR(24)   NOT NULL,
    assignee         VARCHAR(120)  NULL,
    checklist        TEXT          NULL,
    recommendation   VARCHAR(10)   NULL,
    recommended_by   VARCHAR(120)  NULL,
    recommended_at   DATETIME(6)   NULL,
    recommendation_note VARCHAR(1000) NULL,
    decision         VARCHAR(10)   NULL,
    decided_by       VARCHAR(120)  NULL,
    decided_at       DATETIME(6)   NULL,
    decision_note    VARCHAR(1000) NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT fk_kyb_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT chk_kyb_status CHECK (status IN ('OPEN','AWAITING_MERCHANT','AWAITING_DECISION','APPROVED','REJECTED')),
    CONSTRAINT chk_kyb_four_eyes CHECK (decided_by IS NULL OR (recommended_by IS NOT NULL AND decided_by <> recommended_by)),
    INDEX idx_kyb_status (status, created_at),
    INDEX idx_kyb_merchant (merchant_id)
);

-- Where a merchant is paid, per currency. Added by the merchant, verified by PayPort staff before
-- any payout can use it (a changed payout account is a classic fraud route).
CREATE TABLE merchant_settlement_destinations (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    merchant_id      BIGINT        NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    type             VARCHAR(20)   NOT NULL,
    account_name     VARCHAR(200)  NOT NULL,
    account_number   VARCHAR(64)   NOT NULL,
    institution      VARCHAR(120)  NOT NULL,
    branch           VARCHAR(120)  NULL,
    status           VARCHAR(24)   NOT NULL,
    requested_by     VARCHAR(120)  NOT NULL,
    requested_at     DATETIME(6)   NOT NULL,
    verified_by      VARCHAR(120)  NULL,
    verified_at      DATETIME(6)   NULL,
    review_note      VARCHAR(500)  NULL,
    CONSTRAINT fk_msd_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT chk_msd_type CHECK (type IN ('BANK','MOBILE_WALLET')),
    CONSTRAINT chk_msd_status CHECK (status IN ('PENDING_VERIFICATION','VERIFIED','REJECTED','RETIRED')),
    CONSTRAINT chk_msd_four_eyes CHECK (verified_by IS NULL OR verified_by <> requested_by),
    INDEX idx_msd_merchant (merchant_id, currency, status)
);

-- Market enablements: which currencies a merchant may take LIVE payments in.
CREATE TABLE merchant_enablements (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    merchant_id  BIGINT        NOT NULL,
    country      CHAR(2)       NOT NULL,
    currency     VARCHAR(3)    NOT NULL,
    status       VARCHAR(10)   NOT NULL,
    enabled_by   VARCHAR(120)  NOT NULL,
    enabled_at   DATETIME(6)   NOT NULL,
    CONSTRAINT fk_men_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT uq_men UNIQUE (merchant_id, country, currency),
    CONSTRAINT chk_men_status CHECK (status IN ('ENABLED','DISABLED'))
);

-- Merchants already live keep what they can do today (both currencies); new merchants are enabled by staff.
INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at)
SELECT id, 'ZW', 'USD', 'ENABLED', 'MIGRATION:V42', NOW(6) FROM merchants WHERE status = 'ACTIVE';
INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at)
SELECT id, 'ZW', 'ZWG', 'ENABLED', 'MIGRATION:V42', NOW(6) FROM merchants WHERE status = 'ACTIVE';
