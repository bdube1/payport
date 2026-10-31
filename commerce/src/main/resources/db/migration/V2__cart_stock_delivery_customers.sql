-- PayPort Commerce Phase 2a (owner, 2026-10-31): stock, a cart with several items, pickup or delivery, and customers.

-- Stock: NULL means not counted (made to order, services); a number is what is left to sell.
ALTER TABLE products ADD COLUMN stock INT NULL;
ALTER TABLE products ADD CONSTRAINT chk_product_stock CHECK (stock IS NULL OR stock >= 0);

-- How customers receive their order (set by the merchant).
ALTER TABLE stores ADD COLUMN pickup_enabled   BOOLEAN      NOT NULL DEFAULT TRUE;
ALTER TABLE stores ADD COLUMN pickup_note      VARCHAR(300) NULL;
ALTER TABLE stores ADD COLUMN delivery_enabled BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE stores ADD COLUMN delivery_fee_minor BIGINT     NOT NULL DEFAULT 0;
ALTER TABLE stores ADD COLUMN free_delivery_from_minor BIGINT NULL;
ALTER TABLE stores ADD COLUMN delivery_note    VARCHAR(300) NULL;

-- Carts live on the server, found by a random cookie; one store and one currency per cart.
CREATE TABLE carts (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash  CHAR(64)    NOT NULL,
    store_id    BIGINT      NOT NULL,
    currency    CHAR(3)     NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uq_cart UNIQUE (token_hash, store_id),
    INDEX idx_cart_updated (updated_at)
);
CREATE TABLE cart_items (
    cart_id     BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT    NOT NULL,
    PRIMARY KEY (cart_id, product_id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT chk_cart_qty CHECK (quantity BETWEEN 1 AND 50)
);

-- What was ordered, at the price of the moment.
CREATE TABLE order_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT       NOT NULL,
    product_id  BIGINT       NULL,
    name        VARCHAR(160) NOT NULL,
    unit_minor  BIGINT       NOT NULL,
    quantity    INT          NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    INDEX idx_order_items_order (order_id)
);

ALTER TABLE orders ADD COLUMN items_minor        BIGINT       NULL;
ALTER TABLE orders ADD COLUMN delivery_method    VARCHAR(10)  NOT NULL DEFAULT 'NONE';
ALTER TABLE orders ADD COLUMN delivery_fee_minor BIGINT       NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN delivery_address   VARCHAR(300) NULL;
ALTER TABLE orders ADD COLUMN delivery_status    VARCHAR(16)  NOT NULL DEFAULT 'NOT_NEEDED';
ALTER TABLE orders ADD COLUMN stock_taken        BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN stock_short        BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD CONSTRAINT chk_order_delivery CHECK (delivery_method IN ('NONE','PICKUP','DELIVERY'));
ALTER TABLE orders ADD CONSTRAINT chk_order_delivery_status CHECK (delivery_status IN ('NOT_NEEDED','PREPARING','READY','OUT','DELIVERED','COLLECTED'));

-- The merchant's customers (from paid orders): name, phone, how many orders and how much.
CREATE TABLE customers (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    name          VARCHAR(120) NULL,
    orders        INT          NOT NULL DEFAULT 0,
    first_order_at DATETIME(6) NOT NULL,
    last_order_at DATETIME(6)  NOT NULL,
    CONSTRAINT uq_customer UNIQUE (merchant_id, phone)
);
CREATE TABLE customer_spend (
    customer_id  BIGINT  NOT NULL,
    currency     CHAR(3) NOT NULL,
    spent_minor  BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (customer_id, currency),
    CONSTRAINT fk_spend_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
);
