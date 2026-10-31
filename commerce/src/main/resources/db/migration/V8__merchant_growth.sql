-- Owner, 31 Oct 2026: merchant growth tools. Offers and reminders go only to customers who said yes at checkout, and
-- every message carries a link to stop them. Loyalty points are earned on paid orders and spent at checkout.

CREATE TABLE marketing_consent (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL,
    phone9        VARCHAR(9)   NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    name          VARCHAR(120) NULL,
    consented_at  DATETIME(6)  NOT NULL,
    withdrawn_at  DATETIME(6)  NULL,
    stop_token    CHAR(32)     NOT NULL,
    CONSTRAINT uq_consent UNIQUE (merchant_id, phone9),
    CONSTRAINT uq_consent_token UNIQUE (stop_token)
);

CREATE TABLE campaigns (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT       NOT NULL,
    text        VARCHAR(160) NOT NULL,
    recipients  INT          NOT NULL,
    sent_by     VARCHAR(100) NULL,
    created_at  DATETIME(6)  NOT NULL,
    INDEX idx_campaigns (merchant_id, created_at)
);

ALTER TABLE carts ADD COLUMN shopper_id BIGINT NULL;
ALTER TABLE carts ADD COLUMN reminded_at DATETIME(6) NULL;
ALTER TABLE stores ADD COLUMN cart_reminders BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE stores ADD COLUMN loyalty_enabled BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE loyalty_points (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT      NOT NULL,
    phone9      VARCHAR(9)  NOT NULL,
    points      INT         NOT NULL,
    reason      VARCHAR(20) NOT NULL,
    order_ref   VARCHAR(20) NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT uq_points_order UNIQUE (merchant_id, order_ref, reason),
    INDEX idx_points (merchant_id, phone9)
);
ALTER TABLE orders ADD COLUMN points_used INT NOT NULL DEFAULT 0;
