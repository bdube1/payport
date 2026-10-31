-- Owner, 31 Oct 2026: stores differ (products, clothing, food, drinks, services...), merchants group their items their
-- own way, run promotions and discounts, accept pay on delivery, and PayPort lists every store at the front door.
ALTER TABLE stores ADD COLUMN business_type VARCHAR(12)  NOT NULL DEFAULT 'SHOP';
ALTER TABLE stores ADD COLUMN announcement  VARCHAR(200) NULL;
ALTER TABLE stores ADD COLUMN cod_enabled   BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE stores ADD COLUMN listed        BOOLEAN      NOT NULL DEFAULT TRUE;
ALTER TABLE stores ADD CONSTRAINT chk_store_type CHECK (business_type IN ('SHOP','CLOTHING','FOOD','DRINKS','SERVICES','BEAUTY','ELECTRONICS','HOME','CRAFTS','OTHER'));

-- A product is goods or a service (no stock, no delivery); a sale shows the old price struck through; featured items lead the store.
ALTER TABLE products ADD COLUMN kind             VARCHAR(8) NOT NULL DEFAULT 'GOODS';
ALTER TABLE products ADD COLUMN compare_at_minor BIGINT     NULL;
ALTER TABLE products ADD COLUMN featured         BOOLEAN    NOT NULL DEFAULT FALSE;
ALTER TABLE products ADD CONSTRAINT chk_product_kind CHECK (kind IN ('GOODS','SERVICE'));

-- Collections: the merchant's own groups (Men, Women, Kids, Breakfast, Hair...), in the merchant's order.
CREATE TABLE collections (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT      NOT NULL,
    store_id    BIGINT      NOT NULL,
    name        VARCHAR(60) NOT NULL,
    slug        VARCHAR(70) NOT NULL,
    position    INT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_collection UNIQUE (store_id, slug)
);
CREATE TABLE product_collections (
    collection_id BIGINT NOT NULL,
    product_id    BIGINT NOT NULL,
    PRIMARY KEY (collection_id, product_id),
    CONSTRAINT fk_pc_collection FOREIGN KEY (collection_id) REFERENCES collections (id) ON DELETE CASCADE,
    CONSTRAINT fk_pc_product FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE
);

-- Discount codes: a percentage or an amount off the items, with an optional minimum, dates and number of uses.
CREATE TABLE discount_codes (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id     BIGINT      NOT NULL,
    store_id        BIGINT      NOT NULL,
    code            VARCHAR(30) NOT NULL,
    kind            VARCHAR(8)  NOT NULL,
    value           BIGINT      NOT NULL,
    min_order_minor BIGINT      NULL,
    ends_at         DATETIME(6) NULL,
    max_uses        INT         NULL,
    used            INT         NOT NULL DEFAULT 0,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT uq_discount UNIQUE (store_id, code),
    CONSTRAINT chk_discount_kind CHECK (kind IN ('PERCENT','AMOUNT')),
    CONSTRAINT chk_discount_value CHECK (value > 0 AND (kind <> 'PERCENT' OR value <= 90))
);

-- Pay on delivery or collection: the order waits as ON_DELIVERY (items held) until the merchant records the cash.
ALTER TABLE orders ADD COLUMN pay_method     VARCHAR(8)  NOT NULL DEFAULT 'ONLINE';
ALTER TABLE orders ADD COLUMN discount_code  VARCHAR(30) NULL;
ALTER TABLE orders ADD COLUMN discount_minor BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE orders DROP CHECK chk_order_payment;
ALTER TABLE orders ADD CONSTRAINT chk_order_payment CHECK (payment_status IN ('NOT_STARTED','AWAITING_PAYMENT','PENDING','CONFIRMING','PAID','FAILED','EXPIRED','ON_DELIVERY'));
ALTER TABLE orders ADD CONSTRAINT chk_order_pay_method CHECK (pay_method IN ('ONLINE','CASH'));
