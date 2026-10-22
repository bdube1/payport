-- =====================================================================
-- Phase 8: additional products, each switched on only after its licensing question is answered.
--   service_lines: one row per product line with its licensing question and status. The licence
--     status is a two-officer compliance decision; a line (and any channel gated on it) can only be
--     switched on when the status is GRANTED or NOT_REQUIRED (database CHECK as well as code).
--   ussd_sessions: idempotency for USSD payments (a gateway retry never pays twice).
--   fx_rates: reference rates, four-eyes (maker ≠ checker, database CHECK). Quotes only; executing
--     conversions stays parked until the FX licence question is answered.
--   market_currencies.iso_numeric: ISO 4217 numeric code (EMV QR tag 53).
-- =====================================================================

CREATE TABLE service_lines (
    code              VARCHAR(40)   NOT NULL PRIMARY KEY,
    name              VARCHAR(100)  NOT NULL,
    description       VARCHAR(500)  NOT NULL,
    licence_question  VARCHAR(1000) NOT NULL,
    licence_status    VARCHAR(20)   NOT NULL DEFAULT 'NOT_ASSESSED',
    licence_reference VARCHAR(200)  NULL,
    regulator         VARCHAR(200)  NULL,
    decided_by        VARCHAR(200)  NULL,
    decided_at        DATETIME(6)   NULL,
    enabled           BOOLEAN       NOT NULL DEFAULT FALSE,
    harvest_status    VARCHAR(30)   NOT NULL,
    updated_by        VARCHAR(160)  NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    CONSTRAINT chk_svl_status CHECK (licence_status IN ('NOT_ASSESSED','APPLIED','GRANTED','NOT_REQUIRED','REFUSED')),
    CONSTRAINT chk_svl_enabled CHECK (enabled = FALSE OR licence_status IN ('GRANTED','NOT_REQUIRED')),
    CONSTRAINT chk_svl_harvest CHECK (harvest_status IN ('BUILT','PARKED'))
);

INSERT INTO service_lines (code, name, description, licence_question, harvest_status, updated_by, updated_at) VALUES
('QR_PAYMENTS', 'QR payments', 'EMVCo merchant-presented QR for checkout sessions (dynamic, with amount).',
 'Does accepting QR payments for merchants need an RBZ authorisation beyond the existing payment-service licence, and must PayPort join the national QR scheme (ZimSwitch / RBZ standard) before issuing codes?', 'BUILT', 'MIGRATION:V51', NOW(6)),
('FX_CONVERSION', 'FX quotes and conversions', 'Reference USD/ZWG rates and customer quotes; conversion execution stays parked.',
 'Is PayPort permitted to quote and execute currency conversions (bureau de change / authorised dealer rules under the Exchange Control Act), and which reference rate must be used?', 'BUILT', 'MIGRATION:V51', NOW(6)),
('WALLETS', 'Stored-value wallets', 'Customer e-money wallets (parked code on psp-foundation).',
 'Does PayPort hold or need an e-money / mobile money licence (National Payment Systems Act) and a trust account arrangement for customer balances?', 'PARKED', 'MIGRATION:V51', NOW(6)),
('AGENTS', 'Agent network', 'Cash-in / cash-out agents (parked code on psp-foundation).',
 'Is an agent banking or agent network authorisation required, and what are the RBZ agent due-diligence and float rules?', 'PARKED', 'MIGRATION:V51', NOW(6)),
('WHATSAPP_CHANNEL', 'WhatsApp payments', 'Conversational payments over WhatsApp (parked code on psp-foundation).',
 'Do RBZ consumer-protection and data-protection rules (Cyber and Data Protection Act) allow payment initiation over WhatsApp, and is a Meta Business Solution Provider contract in place?', 'PARKED', 'MIGRATION:V51', NOW(6)),
('POS_ACQUIRING', 'Point-of-sale acquiring', 'Card and wallet payments at terminals.',
 'Is a card acquiring licence or a sponsor acquirer arrangement (and PCI DSS scope) in place for terminals?', 'PARKED', 'MIGRATION:V51', NOW(6));

CREATE TABLE ussd_sessions (
    session_id   VARCHAR(100)  NOT NULL PRIMARY KEY,
    msisdn       VARCHAR(20)   NOT NULL,
    reference    VARCHAR(64)   NULL,
    last_text    VARCHAR(500)  NULL,
    outcome      VARCHAR(500)  NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL
);

CREATE TABLE fx_rates (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    base_ccy       CHAR(3)       NOT NULL,
    quote_ccy      CHAR(3)       NOT NULL,
    rate           DECIMAL(20,8) NOT NULL,
    spread_bps     INT           NOT NULL DEFAULT 0,
    source         VARCHAR(200)  NOT NULL,
    status         VARCHAR(10)   NOT NULL,
    created_by     VARCHAR(160)  NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    approved_by    VARCHAR(160)  NULL,
    approved_at    DATETIME(6)   NULL,
    CONSTRAINT chk_fx_status CHECK (status IN ('PENDING','ACTIVE','RETIRED','REJECTED')),
    CONSTRAINT chk_fx_rate CHECK (rate > 0),
    CONSTRAINT chk_fx_spread CHECK (spread_bps BETWEEN 0 AND 2000),
    CONSTRAINT chk_fx_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT chk_fx_active_approved CHECK (status <> 'ACTIVE' OR approved_by IS NOT NULL),
    INDEX idx_fx_pair (base_ccy, quote_ccy, status)
);

ALTER TABLE market_currencies ADD COLUMN iso_numeric CHAR(3) NULL;
UPDATE market_currencies SET iso_numeric = '840' WHERE currency = 'USD';
UPDATE market_currencies SET iso_numeric = '924' WHERE currency = 'ZWG';
UPDATE market_currencies SET iso_numeric = '967' WHERE currency = 'ZMW';
