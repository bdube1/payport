-- Owner, 31 Oct 2026: several pictures per product, chat with the seller (encrypted), reports of sellers and
-- customers, and saved products.

CREATE TABLE product_images (
    product_id BIGINT      NOT NULL,
    position   INT         NOT NULL,
    image_id   VARCHAR(32) NOT NULL,
    PRIMARY KEY (product_id, position)
);

INSERT INTO product_images (product_id, position, image_id)
SELECT id, 0, image_id FROM products WHERE image_id IS NOT NULL;

-- A conversation between a customer and a store. Names, phone numbers and messages are stored encrypted
-- (AES-GCM); phone_hash finds a WhatsApp, USSD or app customer's conversation again without the number itself.
CREATE TABLE chats (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id        BIGINT       NOT NULL,
    merchant_id     BIGINT       NOT NULL,
    channel         VARCHAR(10)  NOT NULL,
    shopper_id      BIGINT       NULL,
    token_hash      CHAR(64)     NULL,
    phone_hash      CHAR(64)     NULL,
    phone_enc       VARCHAR(255) NULL,
    name_enc        VARCHAR(255) NULL,
    product_id      BIGINT       NULL,
    unread_merchant INT          NOT NULL DEFAULT 0,
    unread_customer INT          NOT NULL DEFAULT 0,
    blocked         BOOLEAN      NOT NULL DEFAULT FALSE,
    last_at         DATETIME(6)  NOT NULL,
    notified_at     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT chk_chat_channel CHECK (channel IN ('WEB', 'APP', 'WHATSAPP', 'USSD')),
    INDEX idx_chats_merchant (merchant_id, last_at),
    INDEX idx_chats_token (token_hash),
    INDEX idx_chats_shopper (shopper_id, store_id),
    INDEX idx_chats_phone (phone_hash, store_id)
);

CREATE TABLE chat_messages (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_id    BIGINT      NOT NULL,
    sender     VARCHAR(10) NOT NULL,
    body_enc   TEXT        NOT NULL,
    topic      VARCHAR(20) NULL,
    flag       VARCHAR(20) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_chat_sender CHECK (sender IN ('CUSTOMER', 'SELLER')),
    INDEX idx_chat_messages (chat_id, id),
    INDEX idx_chat_topics (topic, created_at)
);

CREATE TABLE conduct_reports (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id      BIGINT       NOT NULL,
    merchant_id   BIGINT       NOT NULL,
    by_side       VARCHAR(10)  NOT NULL,
    chat_id       BIGINT       NULL,
    reason        VARCHAR(30)  NOT NULL,
    details_enc   TEXT         NULL,
    complaint_ref VARCHAR(30)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT chk_report_side CHECK (by_side IN ('CUSTOMER', 'SELLER')),
    INDEX idx_reports_merchant (merchant_id, created_at)
);

CREATE TABLE saved_products (
    shopper_id BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (shopper_id, product_id)
);
