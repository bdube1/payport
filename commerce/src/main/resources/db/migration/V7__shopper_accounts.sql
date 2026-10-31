-- Owner, 31 Oct 2026: shoppers sign in with their PayPort login and get order history, saved addresses, favourite
-- stores and reorder. Only what the store needs is kept: PayPort's customer id, name and phone.
CREATE TABLE shoppers (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    psp_user_id BIGINT       NOT NULL,
    name        VARCHAR(120) NULL,
    phone       VARCHAR(20)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uq_shopper_psp UNIQUE (psp_user_id)
);
CREATE TABLE shopper_sessions (
    token_hash CHAR(64)    PRIMARY KEY,
    shopper_id BIGINT      NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_shopper_sessions (shopper_id)
);
CREATE TABLE shopper_addresses (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    shopper_id BIGINT       NOT NULL,
    address    VARCHAR(300) NOT NULL,
    used_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_shopper_address UNIQUE (shopper_id, address)
);
CREATE TABLE favourites (
    shopper_id BIGINT      NOT NULL,
    store_id   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shopper_id, store_id)
);
ALTER TABLE orders ADD COLUMN shopper_id BIGINT NULL;
CREATE INDEX idx_orders_shopper ON orders (shopper_id);
