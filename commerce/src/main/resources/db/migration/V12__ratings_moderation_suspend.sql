-- Owner, 1 Nov 2026: customers rate stores; Payport can disable a store (by hand, or automatically when prohibited
-- content is found); what the automatic checks found is kept.

ALTER TABLE stores
    ADD COLUMN suspended_reason VARCHAR(200) NULL,
    ADD COLUMN suspended_by     VARCHAR(80)  NULL,
    ADD COLUMN suspended_at     DATETIME(6)  NULL;

CREATE TABLE store_ratings (
    shopper_id BIGINT      NOT NULL,
    store_id   BIGINT      NOT NULL,
    stars      INT         NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shopper_id, store_id),
    CONSTRAINT chk_store_rating CHECK (stars BETWEEN 1 AND 5),
    INDEX idx_store_ratings_store (store_id)
);

CREATE TABLE moderation_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT       NOT NULL,
    store_id    BIGINT       NULL,
    product_id  BIGINT       NULL,
    kind        VARCHAR(10)  NOT NULL,
    reason      VARCHAR(200) NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    INDEX idx_moderation_merchant (merchant_id, created_at)
);
