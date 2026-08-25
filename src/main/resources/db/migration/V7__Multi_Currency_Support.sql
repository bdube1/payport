-- ============================================================
-- V7 : Multi-Currency Support
-- Adds product_currencies table so ops can enable/disable
-- USD and ZWG independently per product.
-- Also adds DATA bundle products for all 3 MNOs.
-- ============================================================

-- ── 1. Ensure product_types has currency column (already in V1) ─────────────
-- (V1 already has currency VARCHAR(3) on product_types, nothing to do)

-- ── 2. Create product_currencies table ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS product_currencies (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_type_id     BIGINT          NOT NULL,
    currency            VARCHAR(3)      NOT NULL,   -- USD | ZWG
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    min_amount_cents    BIGINT,                     -- NULL = inherit from product_types
    max_amount_cents    BIGINT,                     -- NULL = inherit from product_types
    label               VARCHAR(20),                -- display label e.g. "USD $"
    display_order       INT             NOT NULL DEFAULT 0,
    updated_by          VARCHAR(100),
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_currency UNIQUE (product_type_id, currency),
    CONSTRAINT fk_pc_product FOREIGN KEY (product_type_id) REFERENCES product_types(id)
);

-- ── 3. Seed USD currency for all existing airtime products ──────────────────
-- AIRTIME
INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $', 0 FROM product_types WHERE code = 'AIRTIME_ECONET';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $', 0 FROM product_types WHERE code = 'AIRTIME_NETONE';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $', 0 FROM product_types WHERE code = 'AIRTIME_TELECEL';

-- ZWG for airtime (disabled by default — ops enables when ready)
INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'ZWG', TRUE, 'ZWG ZiG', 1 FROM product_types WHERE code = 'AIRTIME_ECONET';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'ZWG', TRUE, 'ZWG ZiG', 1 FROM product_types WHERE code = 'AIRTIME_NETONE';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'ZWG', TRUE, 'ZWG ZiG', 1 FROM product_types WHERE code = 'AIRTIME_TELECEL';

-- ── 4. Add DATA bundle products for all 3 MNOs ──────────────────────────────
INSERT IGNORE INTO product_types (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents, currency)
VALUES
('DATA_NETONE',  'NetOne Data Bundle',  'DATA', 'NETONE',  TRUE,  30, 500000, 'USD'),
('DATA_ECONET',  'Econet Data Bundle',  'DATA', 'ECONET',  TRUE, 30, 500000, 'USD'),
('DATA_TELECEL', 'Telecel Data Bundle', 'DATA', 'TELECEL', TRUE, 30, 500000, 'USD');

-- DATA bundle currencies
-- NetOne: USD active (endpoint is live), ZWG available
INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE,  'USD $',   0 FROM product_types WHERE code = 'DATA_NETONE';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'ZWG', TRUE, 'ZWG ZiG', 1 FROM product_types WHERE code = 'DATA_NETONE';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $',   0 FROM product_types WHERE code = 'DATA_ECONET';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'ZWG', TRUE, 'ZWG ZiG', 1 FROM product_types WHERE code = 'DATA_ECONET';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $',   0 FROM product_types WHERE code = 'DATA_TELECEL';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'ZWG', TRUE, 'ZWG ZiG', 1 FROM product_types WHERE code = 'DATA_TELECEL';

-- ── 5. Seed currencies for eSolutions bill payment products (from V6) ────────
-- ZESA: both USD and ZWG (per e-Solutions spec, ZWG meters and USD meters exist)
INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $',   0 FROM product_types WHERE code = 'ZESA_TOKEN';

INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'ZWG', TRUE, 'ZWG ZiG', 1 FROM product_types WHERE code = 'ZESA_TOKEN';

-- Other bill payments: USD only (activate when credentials live)
INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $', 0 FROM product_types WHERE code IN ('BULAWAYO_BILL','ZOL_PAYMENT','NYARADZO_PAYMENT','MOONLIGHT_PAYMENT');

-- ── 6. Add ZWG currencies for DSTV / TelOne (USD only currently) ────────────
INSERT IGNORE INTO product_currencies (product_type_id, currency, is_active, label, display_order)
SELECT id, 'USD', TRUE, 'USD $', 0 FROM product_types WHERE code IN ('DSTV_RENEWAL','TELONE_BILL');
