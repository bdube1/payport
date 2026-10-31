-- PayPort Commerce, its own database (payport_commerce) and its own database user, with no rights on PSP tables.
-- Commerce owns stores, products, links, orders and their presentation; the PSP owns money (checkouts, refunds,
-- ledger, settlement). Every table carries merchant_id and every query filters by it.

-- The merchants Commerce has seen (name and currencies copied from the PSP at sign-in, so pages render without it).
CREATE TABLE merchants (
    merchant_id    BIGINT       PRIMARY KEY,
    business_name  VARCHAR(200) NOT NULL,
    currencies     VARCHAR(200) NOT NULL DEFAULT 'USD',
    psp_status     VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    first_seen_at  DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL
);

CREATE TABLE stores (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL,
    slug          VARCHAR(60)  NOT NULL,
    name          VARCHAR(120) NOT NULL,
    about         VARCHAR(600) NULL,
    whatsapp      VARCHAR(20)  NULL,
    theme_color   CHAR(7)      NOT NULL DEFAULT '#0B6E4F',
    logo_image    CHAR(32)     NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_store_merchant UNIQUE (merchant_id),
    CONSTRAINT uq_store_slug UNIQUE (slug),
    CONSTRAINT chk_store_status CHECK (status IN ('OPEN','CLOSED'))
);

-- Images are uploaded by merchants (untrusted): only JPEG and PNG, re-encoded on upload (metadata removed).
CREATE TABLE images (
    id            CHAR(32)     PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL,
    content_type  VARCHAR(20)  NOT NULL,
    bytes         MEDIUMBLOB   NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    width         INT          NOT NULL,
    height        INT          NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    INDEX idx_images_merchant (merchant_id)
);

CREATE TABLE products (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL,
    store_id      BIGINT       NOT NULL,
    slug          VARCHAR(80)  NOT NULL,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(2000) NULL,
    price_minor   BIGINT       NOT NULL,
    currency      CHAR(3)      NOT NULL,
    image_id      CHAR(32)     NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT fk_products_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT uq_product_slug UNIQUE (store_id, slug),
    CONSTRAINT chk_product_status CHECK (status IN ('ACTIVE','HIDDEN')),
    CONSTRAINT chk_product_price CHECK (price_minor > 0),
    INDEX idx_products_merchant (merchant_id, status)
);

-- Payment links: a fixed or open amount, an optional expiry, the merchant's own reference, optionally for a product.
CREATE TABLE links (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL,
    code          CHAR(8)      NOT NULL,
    title         VARCHAR(120) NOT NULL,
    description   VARCHAR(600) NULL,
    currency      CHAR(3)      NOT NULL,
    amount_minor  BIGINT       NULL,
    min_minor     BIGINT       NULL,
    max_minor     BIGINT       NULL,
    reference     VARCHAR(60)  NULL,
    product_id    BIGINT       NULL,
    reusable      BOOLEAN      NOT NULL DEFAULT TRUE,
    expires_at    DATETIME(6)  NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_link_code UNIQUE (code),
    CONSTRAINT fk_links_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_link_status CHECK (status IN ('ACTIVE','DISABLED','USED')),
    CONSTRAINT chk_link_amount CHECK ((amount_minor IS NULL OR amount_minor > 0) AND (min_minor IS NULL OR min_minor > 0)
        AND (max_minor IS NULL OR min_minor IS NULL OR max_minor >= min_minor)),
    INDEX idx_links_merchant (merchant_id, status)
);

-- Orders: order state and payment state are separate. Payment state only becomes PAID from the PSP.
CREATE TABLE orders (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_ref       VARCHAR(20)  NOT NULL,
    merchant_id     BIGINT       NOT NULL,
    store_id        BIGINT       NULL,
    link_id         BIGINT       NULL,
    product_id      BIGINT       NULL,
    item_name       VARCHAR(160) NOT NULL,
    quantity        INT          NOT NULL DEFAULT 1,
    amount_minor    BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    customer_name   VARCHAR(120) NULL,
    customer_phone  VARCHAR(20)  NULL,
    note            VARCHAR(300) NULL,
    order_status    VARCHAR(12)  NOT NULL DEFAULT 'OPEN',
    payment_status  VARCHAR(16)  NOT NULL DEFAULT 'NOT_STARTED',
    refund_status   VARCHAR(12)  NOT NULL DEFAULT 'NONE',
    refunded_minor  BIGINT       NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    view_token_hash CHAR(64)     NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    paid_at         DATETIME(6)  NULL,
    CONSTRAINT uq_order_ref UNIQUE (order_ref),
    CONSTRAINT uq_order_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_order_status CHECK (order_status IN ('OPEN','FULFILLED','CANCELLED')),
    CONSTRAINT chk_order_payment CHECK (payment_status IN ('NOT_STARTED','AWAITING_PAYMENT','PENDING','CONFIRMING','PAID','FAILED','EXPIRED')),
    CONSTRAINT chk_order_refund CHECK (refund_status IN ('NONE','REQUESTED','PARTIAL','REFUNDED')),
    CONSTRAINT chk_order_amount CHECK (amount_minor > 0 AND quantity > 0),
    INDEX idx_orders_merchant (merchant_id, created_at),
    INDEX idx_orders_payment (payment_status, updated_at)
);

-- Each try at paying an order is one PSP checkout (reference <order>-<n>), so a late payment on an earlier try is never lost.
CREATE TABLE order_attempts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    merchant_id     BIGINT       NOT NULL,
    psp_reference   VARCHAR(40)  NOT NULL,
    psp_session_id  BIGINT       NULL,
    checkout_url    VARCHAR(300) NULL,
    status          VARCHAR(16)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uq_attempt_ref UNIQUE (merchant_id, psp_reference),
    CONSTRAINT fk_attempt_order FOREIGN KEY (order_id) REFERENCES orders (id),
    INDEX idx_attempt_order (order_id)
);

-- Refunds: REQUESTED until the PSP says the customer has been paid back.
CREATE TABLE refunds (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    merchant_id     BIGINT       NOT NULL,
    amount_minor    BIGINT       NOT NULL,
    reason          VARCHAR(300) NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    psp_refund_id   BIGINT       NULL,
    requested_by    VARCHAR(120) NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    completed_at    DATETIME(6)  NULL,
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uq_refund_psp UNIQUE (psp_refund_id),
    CONSTRAINT chk_refund_status CHECK (status IN ('REQUESTED','COMPLETED','FAILED')),
    INDEX idx_refund_order (order_id)
);

-- PSP events handled (consumed once each) and where the reader is in the stream.
CREATE TABLE processed_events (
    event_id      CHAR(36)     PRIMARY KEY,
    event_type    VARCHAR(60)  NOT NULL,
    psp_id        BIGINT       NOT NULL,
    processed_at  DATETIME(6)  NOT NULL
);

CREATE TABLE event_cursor (
    name       VARCHAR(20) PRIMARY KEY,
    last_id    BIGINT      NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
INSERT INTO event_cursor (name, last_id, updated_at) VALUES ('psp', 0, NOW(6));

-- Commerce's own append-only events, for the Phase 1 success measures (first link, checkout started, paid).
CREATE TABLE commerce_events (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type   VARCHAR(40)  NOT NULL,
    merchant_id  BIGINT       NOT NULL,
    subject_ref  VARCHAR(40)  NULL,
    detail       VARCHAR(100) NULL,
    occurred_at  DATETIME(6)  NOT NULL,
    INDEX idx_cevents_merchant (merchant_id, event_type, occurred_at)
);
