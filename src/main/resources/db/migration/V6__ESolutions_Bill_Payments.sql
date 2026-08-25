-- ============================================================
-- V6 : e-Solutions Bill Payments Integration
-- ZESA, City of Bulawayo, ZOL, Nyaradzo, Moonlight
-- ============================================================

-- ── Service Providers ──────────────────────────────────────
INSERT IGNORE INTO service_providers (code, name, provider_type, is_active) VALUES
('ZESA',      'ZETDC Prepaid Electricity', 'FULFILMENT', TRUE),
('BULAWAYO',  'City of Bulawayo Bills',    'FULFILMENT', TRUE),
('ZOL',       'ZOL Internet',              'FULFILMENT', TRUE),
('NYARADZO',  'Nyaradzo Life Assurance',   'FULFILMENT', TRUE),
('MOONLIGHT', 'Moonlight Insurance',       'FULFILMENT', TRUE);

-- ── Product Types ──────────────────────────────────────────
-- ZESA: USD min=$1.00, max=$5000.00; ZWG supported via currency field
INSERT IGNORE INTO product_types
    (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents, currency)
VALUES
('ZESA_TOKEN',       'ZESA Prepaid Electricity', 'BILLS', 'ZESA',      TRUE, 100,     500000, 'USD'),
('BULAWAYO_BILL',    'City of Bulawayo Bill',     'BILLS', 'BULAWAYO',  TRUE, 100,     500000, 'USD'),
('ZOL_PAYMENT',      'ZOL Internet Payment',      'BILLS', 'ZOL',       TRUE, 100,     500000, 'USD'),
('NYARADZO_PAYMENT', 'Nyaradzo Life Assurance',   'BILLS', 'NYARADZO',  TRUE, 100,    2000000, 'USD'),
('MOONLIGHT_PAYMENT','Moonlight Insurance',        'BILLS', 'MOONLIGHT', TRUE, 100,    2000000, 'USD');

-- ── Payment methods table V5 enhancements (type, description) ──
-- Add bill payment type if not already present
UPDATE payment_methods SET type = 'MOBILE_MONEY'
    WHERE type IS NULL AND code IN ('ECOCASH','ONEMONEY','TELECASH','OMARI','INNBUCKS');
UPDATE payment_methods SET type = 'CARD'
    WHERE type IS NULL AND code IN ('VISA_MC','ZIMSWITCH');

-- ── Provider Health Baseline for e-Solutions providers ─────
INSERT IGNORE INTO provider_health (provider_code, provider_type, status) VALUES
('ZESA',      'FULFILMENT', 'OPERATIONAL'),
('BULAWAYO',  'FULFILMENT', 'OPERATIONAL'),
('ZOL',       'FULFILMENT', 'OPERATIONAL'),
('NYARADZO',  'FULFILMENT', 'OPERATIONAL'),
('MOONLIGHT', 'FULFILMENT', 'OPERATIONAL');
