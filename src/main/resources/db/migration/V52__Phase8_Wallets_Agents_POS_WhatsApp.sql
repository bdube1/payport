-- =====================================================================
-- Phase 8 completion: wallets, agents, FX conversion execution, POS terminals, WhatsApp, new-market
-- mobile money (MTN MoMo, Airtel Money) and provider settlement on bank statements.
--
-- Everything ships OFF. Each product line still needs its licence status recorded in the portal by two
-- compliance officers (service_lines, V51) and then an administrator to switch it on; the payment
-- methods and the top-up product are inactive until ops activates them; the providers fail closed
-- until their credentials are configured.
--
-- Money is ledger-authoritative: a wallet balance IS the ledger balance of its CUSTOMER_WALLET
-- account (no balance column to drift). CUSTOMER_WALLET and AGENT_FLOAT are liabilities that the
-- ledger refuses to take below zero, so an overspend or a double spend is impossible at the
-- database, not only in code.
-- =====================================================================

-- ── Ledger categories ───────────────────────────────────────────────
INSERT INTO ledger_categories (category, account_type, normal_side, allow_negative, description) VALUES
 ('CUSTOMER_WALLET',          'LIABILITY', 'CREDIT', FALSE, 'Stored value owed to a wallet holder (e-money, held in trust)'),
 ('AGENT_FLOAT',              'LIABILITY', 'CREDIT', FALSE, 'E-value float owed to a cash-in / cash-out agent'),
 ('AGENT_COMMISSION_EXPENSE', 'EXPENSE',   'DEBIT',  TRUE,  'Commission paid to agents for cash-in and cash-out');

-- ── Wallets ─────────────────────────────────────────────────────────
CREATE TABLE wallets (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    wallet_ref     VARCHAR(20)   NOT NULL,
    user_id        BIGINT        NOT NULL,
    phone          VARCHAR(20)   NOT NULL,
    currency       CHAR(3)       NOT NULL,
    entity_code    VARCHAR(30)   NOT NULL,
    tier           VARCHAR(10)   NOT NULL DEFAULT 'TIER1',
    status         VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    status_reason  VARCHAR(300)  NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_by     VARCHAR(160)  NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    CONSTRAINT uq_wallet_ref UNIQUE (wallet_ref),
    CONSTRAINT uq_wallet_user_ccy UNIQUE (user_id, currency),
    CONSTRAINT chk_wallet_status CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT chk_wallet_tier CHECK (tier IN ('TIER1','TIER2','TIER3')),
    INDEX idx_wallet_phone (phone, currency)
);

CREATE TABLE wallet_operations (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    op_ref         VARCHAR(80)   NOT NULL,
    wallet_id      BIGINT        NOT NULL,
    kind           VARCHAR(12)   NOT NULL,
    direction      VARCHAR(2)    NOT NULL,
    amount_minor   BIGINT        NOT NULL,
    currency       CHAR(3)       NOT NULL,
    counterparty   VARCHAR(120)  NULL,
    reference      VARCHAR(80)   NULL,
    entry_ref      VARCHAR(64)   NOT NULL,
    created_by     VARCHAR(160)  NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    CONSTRAINT uq_wop_ref UNIQUE (op_ref, wallet_id),
    CONSTRAINT fk_wop_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT chk_wop_kind CHECK (kind IN ('TOPUP','PAYMENT','P2P_OUT','P2P_IN','CASH_IN','CASH_OUT','FX_OUT','FX_IN','REFUND')),
    CONSTRAINT chk_wop_dir CHECK (direction IN ('CR','DR')),
    CONSTRAINT chk_wop_amount CHECK (amount_minor > 0),
    INDEX idx_wop_wallet (wallet_id, created_at),
    INDEX idx_wop_reference (reference)
);

-- A wallet payment is only taken after the holder confirmed it with their PIN (one row per payment).
CREATE TABLE wallet_payment_authorizations (
    reference      VARCHAR(64)   NOT NULL PRIMARY KEY,
    wallet_id      BIGINT        NOT NULL,
    amount_minor   BIGINT        NOT NULL,
    currency       CHAR(3)       NOT NULL,
    authorized_by  VARCHAR(160)  NOT NULL,
    authorized_at  DATETIME(6)   NOT NULL,
    expires_at     DATETIME(6)   NOT NULL,
    CONSTRAINT fk_wpa_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id)
);

-- ── Agents ──────────────────────────────────────────────────────────
CREATE TABLE agents (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_code      VARCHAR(20)   NOT NULL,
    merchant_id     BIGINT        NOT NULL,
    name            VARCHAR(140)  NOT NULL,
    phone           VARCHAR(20)   NOT NULL,
    location        VARCHAR(200)  NOT NULL,
    country         CHAR(2)       NOT NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    status_reason   VARCHAR(300)  NULL,
    commission_bps  INT           NULL,
    applied_by      VARCHAR(160)  NOT NULL,
    applied_at      DATETIME(6)   NOT NULL,
    decided_by      VARCHAR(160)  NULL,
    decided_at      DATETIME(6)   NULL,
    CONSTRAINT uq_agent_code UNIQUE (agent_code),
    CONSTRAINT uq_agent_merchant UNIQUE (merchant_id),
    CONSTRAINT chk_agent_status CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','REJECTED')),
    CONSTRAINT chk_agent_decided CHECK (status = 'PENDING' OR decided_by IS NOT NULL),
    CONSTRAINT chk_agent_commission CHECK (commission_bps IS NULL OR commission_bps BETWEEN 0 AND 1000)
);

-- Float bought by the agent with a bank deposit: recorded by one person, approved by another.
CREATE TABLE agent_float_deposits (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id        BIGINT        NOT NULL,
    amount_minor    BIGINT        NOT NULL,
    currency        CHAR(3)       NOT NULL,
    bank_reference  VARCHAR(64)   NOT NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    created_by      VARCHAR(160)  NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    approved_by     VARCHAR(160)  NULL,
    approved_at     DATETIME(6)   NULL,
    note            VARCHAR(300)  NULL,
    entry_ref       VARCHAR(64)   NULL,
    CONSTRAINT fk_afd_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT uq_afd_bank_ref UNIQUE (bank_reference, currency),
    CONSTRAINT chk_afd_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT chk_afd_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_afd_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT chk_afd_posted CHECK (status <> 'APPROVED' OR (approved_by IS NOT NULL AND entry_ref IS NOT NULL))
);

CREATE TABLE agent_transactions (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    op_ref                VARCHAR(80)   NOT NULL,
    agent_id              BIGINT        NOT NULL,
    kind                  VARCHAR(10)   NOT NULL,
    wallet_id             BIGINT        NOT NULL,
    amount_minor          BIGINT        NOT NULL,
    currency              CHAR(3)       NOT NULL,
    commission_minor      BIGINT        NOT NULL DEFAULT 0,
    entry_ref             VARCHAR(64)   NOT NULL,
    commission_entry_ref  VARCHAR(64)   NULL,
    created_by            VARCHAR(160)  NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    CONSTRAINT uq_atx_ref UNIQUE (op_ref),
    CONSTRAINT fk_atx_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT fk_atx_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT chk_atx_kind CHECK (kind IN ('CASH_IN','CASH_OUT')),
    INDEX idx_atx_agent (agent_id, created_at)
);

-- Cash-out: the holder creates a one-time code with their PIN; an agent redeems it with the holder's
-- phone. Only a hash is kept; single use; expires.
CREATE TABLE cash_out_codes (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code_hash          CHAR(64)      NOT NULL,
    wallet_id          BIGINT        NOT NULL,
    amount_minor       BIGINT        NOT NULL,
    currency           CHAR(3)       NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'ISSUED',
    expires_at         DATETIME(6)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    redeemed_by_agent  BIGINT        NULL,
    redeemed_at        DATETIME(6)   NULL,
    CONSTRAINT uq_coc_hash UNIQUE (code_hash),
    CONSTRAINT fk_coc_wallet FOREIGN KEY (wallet_id) REFERENCES wallets (id),
    CONSTRAINT chk_coc_status CHECK (status IN ('ISSUED','REDEEMED','EXPIRED','CANCELLED')),
    CONSTRAINT chk_coc_amount CHECK (amount_minor > 0)
);

CREATE TABLE cash_out_attempts (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT        NOT NULL,
    success     BOOLEAN       NOT NULL,
    at          DATETIME(6)   NOT NULL,
    INDEX idx_coa_agent (agent_id, at)
);

-- ── FX conversion execution ─────────────────────────────────────────
CREATE TABLE fx_quotes (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    quote_ref        VARCHAR(40)   NOT NULL,
    user_id          BIGINT        NOT NULL,
    from_wallet_id   BIGINT        NOT NULL,
    to_wallet_id     BIGINT        NOT NULL,
    from_ccy         CHAR(3)       NOT NULL,
    to_ccy           CHAR(3)       NOT NULL,
    amount_minor     BIGINT        NOT NULL,
    converted_minor  BIGINT        NOT NULL,
    rate             VARCHAR(40)   NOT NULL,
    rate_id          BIGINT        NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    expires_at       DATETIME(6)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    executed_at      DATETIME(6)   NULL,
    entry_ref_out    VARCHAR(64)   NULL,
    entry_ref_in     VARCHAR(64)   NULL,
    detail           VARCHAR(300)  NULL,
    CONSTRAINT uq_fxq_ref UNIQUE (quote_ref),
    CONSTRAINT chk_fxq_status CHECK (status IN ('OPEN','EXECUTED','EXPIRED','FAILED')),
    CONSTRAINT chk_fxq_amounts CHECK (amount_minor > 0 AND converted_minor > 0),
    CONSTRAINT chk_fxq_executed CHECK (status <> 'EXECUTED' OR (entry_ref_out IS NOT NULL AND entry_ref_in IS NOT NULL))
);

-- ── POS terminals ───────────────────────────────────────────────────
CREATE TABLE pos_terminals (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    terminal_id      VARCHAR(40)   NOT NULL,
    merchant_id      BIGINT        NOT NULL,
    label            VARCHAR(100)  NOT NULL,
    secret_sealed    VARCHAR(500)  NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    status_reason    VARCHAR(300)  NULL,
    created_by       VARCHAR(160)  NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    last_seen_at     DATETIME(6)   NULL,
    CONSTRAINT uq_pos_terminal UNIQUE (terminal_id),
    CONSTRAINT chk_pos_status CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE TABLE pos_nonces (
    terminal_id  VARCHAR(40)   NOT NULL,
    nonce        VARCHAR(64)   NOT NULL,
    seen_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (terminal_id, nonce),
    INDEX idx_pos_nonce_seen (seen_at)
);

CREATE TABLE pos_sales (
    id                   BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    terminal_id          VARCHAR(40)   NOT NULL,
    merchant_id          BIGINT        NOT NULL,
    sale_ref             VARCHAR(64)   NOT NULL,
    checkout_session_id  BIGINT        NOT NULL,
    amount_minor         BIGINT        NOT NULL,
    currency             CHAR(3)       NOT NULL,
    created_at           DATETIME(6)   NOT NULL,
    CONSTRAINT uq_pos_sale UNIQUE (terminal_id, sale_ref)
);

-- ── WhatsApp ────────────────────────────────────────────────────────
CREATE TABLE whatsapp_sessions (
    wa_id        VARCHAR(20)   NOT NULL PRIMARY KEY,
    session_id   VARCHAR(100)  NOT NULL,
    path         VARCHAR(500)  NOT NULL,
    updated_at   DATETIME(6)   NOT NULL
);

CREATE TABLE whatsapp_messages_seen (
    message_id   VARCHAR(128)  NOT NULL PRIMARY KEY,
    wa_id        VARCHAR(20)   NOT NULL,
    received_at  DATETIME(6)   NOT NULL,
    reply        VARCHAR(1000) NULL,
    sent         BOOLEAN       NOT NULL DEFAULT FALSE,
    send_error   VARCHAR(300)  NULL
);

-- ── Provider settlements on PayPort's bank statements ───────────────
-- A credit from a payment rail (EcoCash, MTN MoMo, ...) settling what it collected: matched by an
-- approved rule, booked Dr BANK_CASH · Cr PROVIDER_RECEIVABLE[provider]. Rules are four-eyes.
CREATE TABLE provider_settlement_rules (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_code         VARCHAR(40)   NOT NULL,
    currency              CHAR(3)       NOT NULL,
    counterparty_pattern  VARCHAR(140)  NULL,
    remittance_pattern    VARCHAR(140)  NULL,
    status                VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    created_by            VARCHAR(160)  NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    approved_by           VARCHAR(160)  NULL,
    approved_at           DATETIME(6)   NULL,
    CONSTRAINT chk_psr_status CHECK (status IN ('PENDING','ACTIVE','RETIRED','REJECTED')),
    CONSTRAINT chk_psr_pattern CHECK (counterparty_pattern IS NOT NULL OR remittance_pattern IS NOT NULL),
    CONSTRAINT chk_psr_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT chk_psr_active CHECK (status <> 'ACTIVE' OR approved_by IS NOT NULL)
);

ALTER TABLE bank_statement_entries DROP CHECK chk_bse_trace;
ALTER TABLE bank_statement_entries ADD CONSTRAINT chk_bse_trace
    CHECK (trace_status IN ('TRACED','NOT_YET_CONFIRMED','AMOUNT_MISMATCH','UNIDENTIFIED','NOT_BOOKED','LEDGER_PENDING','PROVIDER_SETTLEMENT'));

-- ── Catalogue rows (all inactive) ───────────────────────────────────
INSERT INTO payment_methods (code, name, is_active, display_order, type, description) VALUES
 ('WALLET',       'PayPort Wallet', FALSE, 10, 'WALLET',       'Pay from your PayPort wallet balance (PIN required)'),
 ('MTN_MOMO',     'MTN MoMo',       FALSE, 11, 'MOBILE_MONEY', 'MTN Mobile Money (new markets)'),
 ('AIRTEL_MONEY', 'Airtel Money',   FALSE, 12, 'MOBILE_MONEY', 'Airtel Money (new markets)');

INSERT INTO service_providers (code, name, provider_type, is_active) VALUES ('PAYPORT_WALLET', 'PayPort Wallet', 'WALLET', FALSE);

INSERT INTO product_types (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents, currency)
VALUES ('WALLET_TOPUP', 'Wallet top-up', 'WALLET', 'PAYPORT_WALLET', FALSE, 100, 100000000, 'USD');

INSERT INTO platform_channels (code, name, description, enabled, licence_gate, updated_by, updated_at) VALUES
 ('AGENT', 'Agent network', 'Cash-in and cash-out at registered agents', FALSE, 'AGENTS', 'MIGRATION:V52', NOW(6));

UPDATE service_lines SET harvest_status = 'BUILT', updated_by = 'MIGRATION:V52', updated_at = NOW(6),
       description = CASE code
         WHEN 'WALLETS' THEN 'Customer stored-value wallets: top-up, pay, send, convert, cash-out codes. Ledger-authoritative balances.'
         WHEN 'AGENTS' THEN 'Cash-in / cash-out agents with four-eyes float deposits and commissions.'
         WHEN 'WHATSAPP_CHANNEL' THEN 'Conversational payments over the WhatsApp Cloud API (same menus as USSD).'
         WHEN 'POS_ACQUIRING' THEN 'Signed POS terminals creating checkout sessions paid by QR, wallet or mobile money; card acquiring fails closed until an acquirer is configured.'
       END
 WHERE code IN ('WALLETS','AGENTS','WHATSAPP_CHANNEL','POS_ACQUIRING');
UPDATE service_lines SET description = 'Reference USD/ZWG rates, customer quotes and conversions between a customer''s own wallets.',
       updated_by = 'MIGRATION:V52', updated_at = NOW(6) WHERE code = 'FX_CONVERSION';

-- ── Parameters (provisional until compliance confirms them in the portal) ──
INSERT INTO compliance_parameters (param_key, category, value_json, unit, description, source, status, updated_by, updated_at) VALUES
('WALLET.TIER_LIMITS', 'WALLETS',
 '{"TIER1":{"USD":{"max_balance":50000,"daily_out":20000},"ZWG":{"max_balance":1500000,"daily_out":600000},"ZMW":{"max_balance":1000000,"daily_out":400000}},"TIER2":{"USD":{"max_balance":500000,"daily_out":200000},"ZWG":{"max_balance":15000000,"daily_out":6000000},"ZMW":{"max_balance":10000000,"daily_out":4000000}},"TIER3":{"USD":{"max_balance":5000000,"daily_out":1000000},"ZWG":{"max_balance":150000000,"daily_out":30000000},"ZMW":{"max_balance":100000000,"daily_out":20000000}}}',
 'minor units', 'Wallet maximum balance and daily outgoing limit per KYC tier and currency',
 'Placeholder pending the e-money licence conditions (RBZ National Payment Systems Act; BoZ National Payment Systems Directives for Zambia)', 'PROVISIONAL', 'MIGRATION:V52', NOW(6)),
('WALLET.CASH_OUT_CODE_TTL_MINUTES', 'WALLETS', '30', 'minutes', 'How long a cash-out code can be redeemed at an agent', 'PayPort operating practice', 'PROVISIONAL', 'MIGRATION:V52', NOW(6)),
('WALLET.PAYMENT_AUTH_TTL_MINUTES', 'WALLETS', '5', 'minutes', 'How long a PIN-confirmed wallet payment can be taken', 'PayPort operating practice', 'PROVISIONAL', 'MIGRATION:V52', NOW(6)),
('FX.QUOTE_TTL_SECONDS', 'FX', '60', 'seconds', 'How long a customer FX quote can be executed', 'PayPort operating practice', 'PROVISIONAL', 'MIGRATION:V52', NOW(6)),
('AGENT.COMMISSION_BPS', 'AGENTS', '{"CASH_IN":50,"CASH_OUT":100}', 'basis points', 'Default agent commission per operation (an agent may carry its own rate)', 'Placeholder pending the agent agreement', 'PROVISIONAL', 'MIGRATION:V52', NOW(6)),
('AGENT.MAX_CASH_OUT_FAILURES_PER_HOUR', 'AGENTS', '10', 'attempts', 'Wrong cash-out codes an agent may enter per hour before redemption is blocked', 'PayPort fraud control', 'PROVISIONAL', 'MIGRATION:V52', NOW(6)),
('POS.SIGNATURE_WINDOW_SECONDS', 'POS', '300', 'seconds', 'Clock skew accepted on a signed terminal request', 'PayPort security control', 'PROVISIONAL', 'MIGRATION:V52', NOW(6));
