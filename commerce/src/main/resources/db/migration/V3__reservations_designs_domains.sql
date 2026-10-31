-- Review of Phase 2a (31 Oct 2026): stock is RESERVED when the order is placed (atomically, so two customers can
-- never both pay for the last item), becomes SOLD when the PSP confirms payment, and is RELEASED only when the
-- payment definitively did not happen (failed, expired, never started) or the order is cancelled. A payment whose
-- outcome is uncertain keeps its reservation.
ALTER TABLE orders ADD COLUMN stock_state    VARCHAR(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE orders ADD COLUMN reserved_until DATETIME(6) NULL;
ALTER TABLE orders ADD CONSTRAINT chk_order_stock_state CHECK (stock_state IN ('NONE','RESERVED','SOLD','RELEASED'));
UPDATE orders SET stock_state = 'SOLD' WHERE stock_taken = TRUE;
CREATE INDEX idx_orders_reserved ON orders (stock_state, reserved_until);

-- Customers are worked out from paid orders when asked (paid orders only, net of refunds), so the figures can never
-- drift from the orders themselves; the running totals of Phase 2a are removed.
DROP TABLE customer_spend;
DROP TABLE customers;

-- Phase 2b: three fixed store designs, a banner, and categories for the catalogue design.
ALTER TABLE stores ADD COLUMN design       VARCHAR(12) NOT NULL DEFAULT 'PRODUCT';
ALTER TABLE stores ADD COLUMN banner_image CHAR(32)    NULL;
ALTER TABLE stores ADD COLUMN tagline      VARCHAR(120) NULL;
ALTER TABLE stores ADD CONSTRAINT chk_store_design CHECK (design IN ('PRODUCT','BOUTIQUE','LOCAL'));
ALTER TABLE products ADD COLUMN category VARCHAR(40) NULL;

-- Phase 2b: delivery through a provider adapter (MANUAL = the merchant's own delivery; couriers are added as adapters).
ALTER TABLE stores ADD COLUMN delivery_provider VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

-- Phase 2b: custom domains. Proved by a DNS TXT record (_payport.<domain> = the token); HTTPS certificates are only
-- issued for verified domains (Caddy asks /internal/tls/allowed), and a domain belongs to one store at a time.
CREATE TABLE store_domains (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id   BIGINT       NOT NULL,
    store_id      BIGINT       NOT NULL,
    domain        VARCHAR(190) NOT NULL,
    token         CHAR(32)     NOT NULL,
    status        VARCHAR(10)  NOT NULL,
    checked_at    DATETIME(6)  NULL,
    verified_at   DATETIME(6)  NULL,
    last_error    VARCHAR(200) NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_store_domain UNIQUE (domain),
    CONSTRAINT chk_domain_status CHECK (status IN ('PENDING','VERIFIED','FAILED')),
    INDEX idx_domains_store (store_id)
);
