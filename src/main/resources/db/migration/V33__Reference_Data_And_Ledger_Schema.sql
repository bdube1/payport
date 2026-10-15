-- =====================================================================
-- Phase 1: reference data (multi-country from day one) and the double-entry ledger.
-- Design: docs/phase1/PHASE1_DESIGN.md, ADR-0004, ADR-0006, ADR-0007, ADR-0008.
--
-- Ledger tables are written ONLY by the definer procedures in V34. The application
-- user gets SELECT on these tables and EXECUTE on the procedures
-- (scripts/db/grant-least-privilege.sh). No triggers: they need SUPER under binlog
-- and can be dropped or bypassed (TRUNCATE) by a broadly-privileged user.
-- =====================================================================

-- ── Reference data ────────────────────────────────────────────────────

CREATE TABLE ref_currencies (
    code         CHAR(3)      NOT NULL PRIMARY KEY,           -- ISO 4217
    name         VARCHAR(80)  NOT NULL,
    minor_units  TINYINT      NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_ref_ccy_code   CHECK (code REGEXP '^[A-Z]{3}$'),
    CONSTRAINT chk_ref_ccy_minor  CHECK (minor_units BETWEEN 0 AND 4)
);

CREATE TABLE ref_countries (
    code         CHAR(2)      NOT NULL PRIMARY KEY,           -- ISO 3166-1 alpha-2
    name         VARCHAR(80)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_ref_country_code CHECK (code REGEXP '^[A-Z]{2}$')
);

-- The legal entity that holds the books for a market (PayPort's own, or later a partner arrangement).
CREATE TABLE ref_legal_entities (
    code           VARCHAR(30)  NOT NULL PRIMARY KEY,
    legal_name     VARCHAR(200) NOT NULL,
    country_code   CHAR(2)      NOT NULL,
    base_currency  CHAR(3)      NOT NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_entity_country  FOREIGN KEY (country_code)  REFERENCES ref_countries (code),
    CONSTRAINT fk_entity_currency FOREIGN KEY (base_currency) REFERENCES ref_currencies (code),
    CONSTRAINT chk_entity_status  CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

INSERT INTO ref_currencies (code, name, minor_units, enabled) VALUES
    ('USD', 'US Dollar', 2, TRUE),
    ('ZWG', 'Zimbabwe Gold (ZiG)', 2, TRUE);

INSERT INTO ref_countries (code, name, enabled) VALUES ('ZW', 'Zimbabwe', TRUE);

-- Legal name is a placeholder until confirmed by PayPort; the code is what the books use.
INSERT INTO ref_legal_entities (code, legal_name, country_code, base_currency)
VALUES ('PAYPORT_ZW', 'PayPort Zimbabwe (legal name to be confirmed)', 'ZW', 'USD');

-- ── Chart of accounts (categories) ────────────────────────────────────
-- A proposal awaiting finance sign-off (PHASE1_DESIGN.md section 4). Stored as data so the
-- signed-off version is visible and auditable.

CREATE TABLE ledger_categories (
    category        VARCHAR(40)  NOT NULL PRIMARY KEY,
    account_type    VARCHAR(10)  NOT NULL,
    normal_side     VARCHAR(6)   NOT NULL,
    allow_negative  BOOLEAN      NOT NULL,
    description     VARCHAR(300) NOT NULL,
    CONSTRAINT chk_lcat_type CHECK (account_type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    CONSTRAINT chk_lcat_side CHECK (normal_side IN ('DEBIT','CREDIT'))
);

INSERT INTO ledger_categories (category, account_type, normal_side, allow_negative, description) VALUES
 ('BANK_CASH',                 'ASSET',     'DEBIT',  TRUE,  'Money in PayPort (trust) bank accounts'),
 ('PROVIDER_RECEIVABLE',       'ASSET',     'DEBIT',  TRUE,  'Collected by a payment rail, not yet received in the bank'),
 ('VENDOR_FLOAT',              'ASSET',     'DEBIT',  TRUE,  'Prepaid float held at airtime and bill vendors'),
 ('SUSPENSE',                  'ASSET',     'DEBIT',  TRUE,  'Unidentified items awaiting investigation'),
 ('FX_CLEARING',               'ASSET',     'DEBIT',  TRUE,  'Two-leg currency conversions in progress'),
 ('MERCHANT_PAYABLE',          'LIABILITY', 'CREDIT', FALSE, 'Owed to a merchant, not yet in a settlement'),
 ('SETTLEMENT_PAYABLE',        'LIABILITY', 'CREDIT', FALSE, 'Earmarked in a settlement, not yet paid out'),
 ('CUSTOMER_ORDER_OBLIGATION', 'LIABILITY', 'CREDIT', FALSE, 'Consumer paid; airtime or bill not yet delivered'),
 ('CUSTOMER_REFUND_PAYABLE',   'LIABILITY', 'CREDIT', FALSE, 'Refunds owed to customers'),
 ('TAX_PAYABLE',               'LIABILITY', 'CREDIT', TRUE,  'Collected taxes owed to the tax authority'),
 ('FEE_REVENUE',               'REVENUE',   'CREDIT', TRUE,  'PayPort processing fees, net of VAT'),
 ('COMMISSION_REVENUE',        'REVENUE',   'CREDIT', TRUE,  'Commission earned from billers and vendors'),
 ('FX_GAIN_LOSS',              'REVENUE',   'CREDIT', TRUE,  'Realised foreign exchange differences'),
 ('PROVIDER_FEE_EXPENSE',      'EXPENSE',   'DEBIT',  TRUE,  'Charges levied by payment rails'),
 ('LOSS_EXPENSE',              'EXPENSE',   'DEBIT',  TRUE,  'Approved write-offs'),
 ('OPENING_BALANCE_EQUITY',    'EQUITY',    'CREDIT', TRUE,  'Balances brought forward at cut-over');

-- ── Accounts ──────────────────────────────────────────────────────────

CREATE TABLE ledger_accounts (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(200) NOT NULL,       -- entity:book:category:owner:currency
    entity_code     VARCHAR(30)  NOT NULL,
    book            VARCHAR(4)   NOT NULL,
    category        VARCHAR(40)  NOT NULL,
    owner_ref       VARCHAR(100) NOT NULL,
    currency        CHAR(3)      NOT NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    created_at      DATETIME(6)  NOT NULL,
    created_by      VARCHAR(120) NOT NULL,
    CONSTRAINT uq_lacct_code   UNIQUE (code),
    CONSTRAINT uq_lacct_key    UNIQUE (entity_code, book, category, owner_ref, currency),
    CONSTRAINT fk_lacct_entity FOREIGN KEY (entity_code) REFERENCES ref_legal_entities (code),
    CONSTRAINT fk_lacct_ccy    FOREIGN KEY (currency)    REFERENCES ref_currencies (code),
    CONSTRAINT fk_lacct_cat    FOREIGN KEY (category)    REFERENCES ledger_categories (category),
    CONSTRAINT chk_lacct_book  CHECK (book IN ('LIVE','TEST')),
    CONSTRAINT chk_lacct_status CHECK (status IN ('OPEN','CLOSED')),
    CONSTRAINT chk_lacct_owner CHECK (owner_ref <> '' AND owner_ref NOT LIKE '%:%')
);

-- Balance projection, maintained only by ledger_post in the same transaction as the lines.
-- balance is in the account's normal-side terms (debit-normal: debits - credits; else credits - debits).
CREATE TABLE ledger_balances (
    account_id      BIGINT       NOT NULL PRIMARY KEY,
    debit_total     BIGINT       NOT NULL DEFAULT 0,
    credit_total    BIGINT       NOT NULL DEFAULT 0,
    balance         BIGINT       NOT NULL DEFAULT 0,
    last_entry_id   BIGINT       NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT fk_lbal_account FOREIGN KEY (account_id) REFERENCES ledger_accounts (id)
);

-- ── Periods ───────────────────────────────────────────────────────────

CREATE TABLE ledger_periods (
    entity_code  VARCHAR(30)  NOT NULL,
    book         VARCHAR(4)   NOT NULL,
    period       CHAR(7)      NOT NULL,          -- YYYY-MM
    status       VARCHAR(10)  NOT NULL,
    changed_by   VARCHAR(120) NOT NULL,
    changed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (entity_code, book, period),
    CONSTRAINT fk_lper_entity  FOREIGN KEY (entity_code) REFERENCES ref_legal_entities (code),
    CONSTRAINT chk_lper_book   CHECK (book IN ('LIVE','TEST')),
    CONSTRAINT chk_lper_period CHECK (period REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT chk_lper_status CHECK (status IN ('OPEN','CLOSED'))
);

-- ── Journal ───────────────────────────────────────────────────────────

CREATE TABLE ledger_entries (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    entry_ref          VARCHAR(40)  NOT NULL,
    entity_code        VARCHAR(30)  NOT NULL,
    book               VARCHAR(4)   NOT NULL,
    idempotency_key    VARCHAR(190) NOT NULL,
    request_hash       CHAR(64)     NOT NULL,
    entry_type         VARCHAR(10)  NOT NULL,
    template_code      VARCHAR(60)  NULL,
    template_version   INT          NULL,
    effective_date     DATE         NOT NULL,
    period             CHAR(7)      NOT NULL,
    description        VARCHAR(500) NULL,
    source_type        VARCHAR(40)  NULL,
    source_ref         VARCHAR(120) NULL,
    reverses_entry_id  BIGINT       NULL,
    posted_by          VARCHAR(120) NOT NULL,
    posted_at          DATETIME(6)  NOT NULL,
    content_hash       CHAR(64)     NOT NULL,
    CONSTRAINT uq_lent_ref      UNIQUE (entry_ref),
    CONSTRAINT uq_lent_idem     UNIQUE (idempotency_key),
    CONSTRAINT uq_lent_reverses UNIQUE (reverses_entry_id),
    CONSTRAINT fk_lent_reverses FOREIGN KEY (reverses_entry_id) REFERENCES ledger_entries (id),
    CONSTRAINT fk_lent_entity   FOREIGN KEY (entity_code) REFERENCES ref_legal_entities (code),
    CONSTRAINT chk_lent_book    CHECK (book IN ('LIVE','TEST')),
    CONSTRAINT chk_lent_type    CHECK (entry_type IN ('POSTING','REVERSAL')),
    CONSTRAINT chk_lent_reverse CHECK ((entry_type = 'REVERSAL') = (reverses_entry_id IS NOT NULL)),
    INDEX idx_lent_source (source_type, source_ref),
    INDEX idx_lent_entity_date (entity_code, book, effective_date)
);

CREATE TABLE ledger_lines (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    entry_id      BIGINT       NOT NULL,
    line_no       SMALLINT     NOT NULL,
    account_id    BIGINT       NOT NULL,
    direction     VARCHAR(6)   NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    currency      CHAR(3)      NOT NULL,
    memo          VARCHAR(200) NULL,
    CONSTRAINT uq_lline_no      UNIQUE (entry_id, line_no),
    CONSTRAINT fk_lline_entry   FOREIGN KEY (entry_id)   REFERENCES ledger_entries (id),
    CONSTRAINT fk_lline_account FOREIGN KEY (account_id) REFERENCES ledger_accounts (id),
    CONSTRAINT chk_lline_dir    CHECK (direction IN ('DEBIT','CREDIT')),
    CONSTRAINT chk_lline_amount CHECK (amount_minor > 0),
    INDEX idx_lline_account (account_id, entry_id)
);

-- ── Tamper evidence ───────────────────────────────────────────────────
-- Each seal covers a contiguous id range of entries:
-- seal_hash = SHA-256(prev_seal_hash || content_hash of every entry in the range, in id order).
-- Export the latest seal outside the database (ops runbook) so a rewritten chain is detectable.

CREATE TABLE ledger_seals (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    from_entry_id   BIGINT       NOT NULL,
    to_entry_id     BIGINT       NOT NULL,
    entry_count     INT          NOT NULL,
    prev_seal_hash  CHAR(64)     NULL,
    seal_hash       CHAR(64)     NOT NULL,
    sealed_at       DATETIME(6)  NOT NULL,
    sealed_by       VARCHAR(120) NOT NULL,
    CONSTRAINT uq_lseal_from UNIQUE (from_entry_id),
    CONSTRAINT chk_lseal_range CHECK (to_entry_id >= from_entry_id AND entry_count > 0)
);

-- Single row used to serialise sealing.
CREATE TABLE ledger_seal_lock (
    id  TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_lseal_lock CHECK (id = 1)
);
INSERT INTO ledger_seal_lock (id) VALUES (1);
