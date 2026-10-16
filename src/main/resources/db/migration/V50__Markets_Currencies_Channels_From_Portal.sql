-- =====================================================================
-- Markets, currencies and channels are operated from the portal (no code changes):
--  - markets / market_currencies hold each country's settings (were markets/<CC>.json);
--  - platform_channels switches whole channels on and off;
--  - reference data the ledger depends on (countries, currencies, legal entities) stays
--    read-only for the application user; it changes only through the definer procedures below,
--    which the application calls after a two-person approval.
-- Invariants (also checked by MarketRegistry): a SANDBOX market's country is not enabled in
-- ref_countries; a LIVE market's is. A currency belongs to one market. Live implies enabled.
-- =====================================================================

CREATE TABLE markets (
    country             CHAR(2)       NOT NULL PRIMARY KEY,
    name                VARCHAR(80)   NOT NULL,
    status              VARCHAR(10)   NOT NULL,
    status_note         VARCHAR(300)  NULL,
    legal_entity        VARCHAR(30)   NOT NULL,
    timezone            VARCHAR(40)   NOT NULL,
    phone_country_code  VARCHAR(4)    NOT NULL,
    national_pattern    VARCHAR(120)  NOT NULL,
    holidays_parameter  VARCHAR(80)   NOT NULL,
    fiu_report_format   VARCHAR(30)   NOT NULL,
    regulators_json     TEXT          NOT NULL,
    required_lists_json TEXT          NOT NULL,
    bank_name           VARCHAR(140)  NOT NULL,
    bank_bic            VARCHAR(11)   NULL,
    payout_format       VARCHAR(40)   NOT NULL,
    statement_format    VARCHAR(40)   NOT NULL,
    statement_guideline VARCHAR(80)   NULL,
    bank_format_status  VARCHAR(200)  NULL,
    version             INT           NOT NULL DEFAULT 1,
    updated_by          VARCHAR(160)  NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    CONSTRAINT fk_mkt_country FOREIGN KEY (country) REFERENCES ref_countries (code),
    CONSTRAINT fk_mkt_entity FOREIGN KEY (legal_entity) REFERENCES ref_legal_entities (code),
    CONSTRAINT chk_mkt_status CHECK (status IN ('LIVE','SANDBOX','SUSPENDED'))
);

CREATE TABLE market_currencies (
    country               CHAR(2)       NOT NULL,
    currency              CHAR(3)       NOT NULL,
    enabled               BOOLEAN       NOT NULL,
    live                  BOOLEAN       NOT NULL,
    symbol                VARCHAR(8)    NOT NULL,
    checkout_product_type VARCHAR(60)   NOT NULL,
    bank_account_number   VARCHAR(34)   NOT NULL,
    bank_account_name     VARCHAR(140)  NOT NULL,
    ledger_owner          VARCHAR(60)   NOT NULL,
    updated_by            VARCHAR(160)  NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (country, currency),
    CONSTRAINT uq_mcur_currency UNIQUE (currency),
    CONSTRAINT fk_mcur_market FOREIGN KEY (country) REFERENCES markets (country),
    CONSTRAINT fk_mcur_currency FOREIGN KEY (currency) REFERENCES ref_currencies (code),
    CONSTRAINT chk_mcur_live CHECK (live = FALSE OR enabled = TRUE)
);

INSERT INTO markets (country, name, status, status_note, legal_entity, timezone, phone_country_code, national_pattern, holidays_parameter,
                     fiu_report_format, regulators_json, required_lists_json, bank_name, bank_bic, payout_format, statement_format,
                     statement_guideline, bank_format_status, updated_by, updated_at) VALUES
('ZW', 'Zimbabwe', 'LIVE', NULL, 'PAYPORT_ZW', 'Africa/Harare', '263', '^7[1378][0-9]{7}$', 'CALENDAR.ZW.HOLIDAYS', 'GOAML_XML',
 '{"payments":"Reserve Bank of Zimbabwe (National Payment Systems Act [Chapter 24:23])","fiu":"Financial Intelligence Unit, Zimbabwe (goAML)"}',
 '["UN-CONSOLIDATED"]', 'Sponsor bank (to be confirmed)', NULL, 'PAYPORT_CSV_V1', 'ISO20022_CAMT_053_001_08', NULL,
 'PROVISIONAL: the sponsor bank has not yet confirmed its payout and statement formats', 'MIGRATION:V50', NOW(6)),
('ZM', 'Zambia', 'SANDBOX', 'Representative second market. Switch to LIVE from the ops console once licensed (two-person approval).',
 'PAYPORT_ZM_SANDBOX', 'Africa/Lusaka', '260', '^(9[5-7]|7[5-7])[0-9]{7}$', 'CALENDAR.ZM.HOLIDAYS', 'NOT_CONFIGURED',
 '{"payments":"Bank of Zambia (National Payment Systems Act, 2007) - not engaged","fiu":"Financial Intelligence Centre, Zambia - not engaged"}',
 '["UN-CONSOLIDATED"]', 'Sandbox bank (fictitious)', NULL, 'ISO20022_PAIN_001_001_09', 'ISO20022_CAMT_053_001_08', NULL, 'SANDBOX',
 'MIGRATION:V50', NOW(6));

INSERT INTO market_currencies (country, currency, enabled, live, symbol, checkout_product_type, bank_account_number, bank_account_name,
                               ledger_owner, updated_by, updated_at) VALUES
('ZW', 'USD', TRUE, TRUE, '$', 'MERCHANT_CHECKOUT', 'TO-BE-CONFIRMED-USD', 'PayPort Zimbabwe settlement account USD', 'SETTLEMENT_USD', 'MIGRATION:V50', NOW(6)),
('ZW', 'ZWG', TRUE, TRUE, 'ZWG ', 'MERCHANT_CHECKOUT_ZWG', 'TO-BE-CONFIRMED-ZWG', 'PayPort Zimbabwe settlement account ZWG', 'SETTLEMENT_ZWG', 'MIGRATION:V50', NOW(6)),
('ZM', 'ZMW', TRUE, FALSE, 'K', 'MERCHANT_CHECKOUT', 'ZM-SANDBOX-0001', 'PayPort Zambia sandbox account ZMW', 'SANDBOX_ZMW', 'MIGRATION:V50', NOW(6));

-- Channels: a switched-off channel refuses new payments before anything is sent.
CREATE TABLE platform_channels (
    code         VARCHAR(20)   NOT NULL PRIMARY KEY,
    name         VARCHAR(80)   NOT NULL,
    description  VARCHAR(300)  NOT NULL,
    enabled      BOOLEAN       NOT NULL,
    licence_gate VARCHAR(40)   NULL,
    reason       VARCHAR(500)  NULL,
    updated_by   VARCHAR(160)  NOT NULL,
    updated_at   DATETIME(6)   NOT NULL
);

INSERT INTO platform_channels (code, name, description, enabled, licence_gate, updated_by, updated_at) VALUES
('WEB', 'Web app', 'Consumer web app (pay.payport)', TRUE, NULL, 'MIGRATION:V50', NOW(6)),
('MOB', 'Mobile app', 'Consumer mobile app', TRUE, NULL, 'MIGRATION:V50', NOW(6)),
('USSD', 'USSD', 'USSD menus through an aggregator', TRUE, NULL, 'MIGRATION:V50', NOW(6)),
('API', 'Merchant API', 'Checkout sessions created with merchant API keys', TRUE, NULL, 'MIGRATION:V50', NOW(6)),
('PAYMENT_LINK', 'Payment links', 'Merchant payment links', TRUE, NULL, 'MIGRATION:V50', NOW(6)),
('HOSTED_CHECKOUT', 'Hosted checkout', 'The hosted checkout page used by API sessions and links', TRUE, NULL, 'MIGRATION:V50', NOW(6)),
('QR', 'QR (EMV merchant-presented)', 'Pay a checkout session by scanning an EMVCo QR code', FALSE, 'QR_PAYMENTS', 'MIGRATION:V50', NOW(6)),
('WHATSAPP', 'WhatsApp', 'Conversational payments over WhatsApp', FALSE, 'WHATSAPP_CHANNEL', 'MIGRATION:V50', NOW(6)),
('POS', 'Point of sale', 'Card or wallet payments at a terminal', FALSE, 'POS_ACQUIRING', 'MIGRATION:V50', NOW(6));

DELIMITER $$

-- ── Reference data write path for the application (after a two-person approval in the app) ──

CREATE PROCEDURE ref_upsert_country(IN p_code CHAR(2), IN p_name VARCHAR(80))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    IF p_code IS NULL OR p_code NOT REGEXP '^[A-Z]{2}$' OR p_name IS NULL OR p_name = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'REF_INVALID: country code (ISO 3166-1 alpha-2) and name are required';
    END IF;
    INSERT INTO ref_countries (code, name, enabled) VALUES (p_code, p_name, FALSE)
    ON DUPLICATE KEY UPDATE name = VALUES(name);
END$$

CREATE PROCEDURE ref_set_country_enabled(IN p_code CHAR(2), IN p_enabled BOOLEAN)
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    UPDATE ref_countries SET enabled = p_enabled WHERE code = p_code;
    IF ROW_COUNT() = 0 AND NOT EXISTS (SELECT 1 FROM ref_countries WHERE code = p_code) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'REF_NOT_FOUND: unknown country';
    END IF;
END$$

CREATE PROCEDURE ref_upsert_currency(IN p_code CHAR(3), IN p_name VARCHAR(80), IN p_minor TINYINT)
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    IF p_code IS NULL OR p_code NOT REGEXP '^[A-Z]{3}$' OR p_name IS NULL OR p_name = '' OR p_minor NOT BETWEEN 0 AND 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'REF_INVALID: currency code (ISO 4217), name and minor units (0-4) are required';
    END IF;
    INSERT INTO ref_currencies (code, name, minor_units, enabled) VALUES (p_code, p_name, p_minor, FALSE)
    ON DUPLICATE KEY UPDATE name = VALUES(name);
END$$

-- Enabling makes the currency usable in the ledger. The application never disables a currency here:
-- existing balances, refunds and settlements must still post; switching a currency off for new
-- payments is done in market_currencies.
CREATE PROCEDURE ref_enable_currency(IN p_code CHAR(3))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    UPDATE ref_currencies SET enabled = TRUE WHERE code = p_code;
    IF NOT EXISTS (SELECT 1 FROM ref_currencies WHERE code = p_code) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'REF_NOT_FOUND: unknown currency';
    END IF;
END$$

CREATE PROCEDURE ref_upsert_legal_entity(IN p_code VARCHAR(30), IN p_legal_name VARCHAR(200), IN p_country CHAR(2), IN p_base_ccy CHAR(3))
    SQL SECURITY DEFINER
    MODIFIES SQL DATA
BEGIN
    IF p_code IS NULL OR p_code NOT REGEXP '^[A-Z0-9_]{2,30}$' OR p_legal_name IS NULL OR p_legal_name = '' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'REF_INVALID: entity code (A-Z, 0-9, _) and legal name are required';
    END IF;
    INSERT INTO ref_legal_entities (code, legal_name, country_code, base_currency, status) VALUES (p_code, p_legal_name, p_country, p_base_ccy, 'ACTIVE')
    ON DUPLICATE KEY UPDATE legal_name = VALUES(legal_name);
END$$

DELIMITER ;
