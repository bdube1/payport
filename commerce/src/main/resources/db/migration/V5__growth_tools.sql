-- Owner, 31 Oct 2026: payment options, reviews, delivery zones, bookings for services, order alerts.

-- The payment methods the merchant accepts, as PayPort Payments last said (comma list); the customer's choice per order.
ALTER TABLE merchants ADD COLUMN methods    VARCHAR(200) NULL;
ALTER TABLE merchants ADD COLUMN methods_at DATETIME(6)  NULL;
ALTER TABLE orders    ADD COLUMN pay_choice VARCHAR(20)  NULL;

-- Reviews: only from customers with a paid order, one per product per order; the merchant can reply or hide one.
CREATE TABLE reviews (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT       NOT NULL,
    store_id    BIGINT       NOT NULL,
    product_id  BIGINT       NOT NULL,
    order_id    BIGINT       NOT NULL,
    rating      INT          NOT NULL,
    comment     VARCHAR(600) NULL,
    name        VARCHAR(60)  NULL,
    reply       VARCHAR(600) NULL,
    hidden      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uq_review UNIQUE (order_id, product_id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
    INDEX idx_reviews_product (product_id, hidden),
    INDEX idx_reviews_store (store_id, hidden)
);

-- Delivery zones: the merchant's own areas, each with its fee (and free delivery above an amount).
CREATE TABLE delivery_zones (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id     BIGINT      NOT NULL,
    store_id        BIGINT      NOT NULL,
    name            VARCHAR(60) NOT NULL,
    fee_minor       BIGINT      NOT NULL DEFAULT 0,
    free_from_minor BIGINT      NULL,
    position        INT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_zone UNIQUE (store_id, name)
);
ALTER TABLE orders ADD COLUMN delivery_zone VARCHAR(60) NULL;

-- Bookings: services take time; the store's opening hours and how many people serve at once decide the free times.
ALTER TABLE products ADD COLUMN duration_min INT NULL;
ALTER TABLE stores   ADD COLUMN booking_staff INT NOT NULL DEFAULT 1;
CREATE TABLE store_hours (
    store_id BIGINT NOT NULL,
    weekday  INT    NOT NULL,
    opens    TIME   NOT NULL,
    closes   TIME   NOT NULL,
    PRIMARY KEY (store_id, weekday),
    CONSTRAINT chk_hours_day CHECK (weekday BETWEEN 1 AND 7)
);
CREATE TABLE bookings (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT      NOT NULL,
    store_id    BIGINT      NOT NULL,
    merchant_id BIGINT      NOT NULL,
    starts_at   DATETIME    NOT NULL,
    ends_at     DATETIME    NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT uq_booking_order UNIQUE (order_id),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING','HELD','CONFIRMED','RELEASED')),
    INDEX idx_bookings_time (store_id, starts_at)
);

-- Alerts: a text to the merchant for each new order; texts to the customer when the order is confirmed or ready.
ALTER TABLE stores ADD COLUMN alerts_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE stores ADD COLUMN customer_sms   BOOLEAN NOT NULL DEFAULT TRUE;
