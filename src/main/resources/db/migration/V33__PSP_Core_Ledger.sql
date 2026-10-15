-- =====================================================================
-- Core ledger: double-entry, append-only, the financial source of truth.
-- Amounts are BIGINT minor units (cents). One account per
-- (category, owner, currency); USD and ZWG never share an account.
-- =====================================================================

CREATE TABLE psp_ledger_accounts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(120) NOT NULL,         -- e.g. MERCHANT_AVAILABLE:42:USD
    category        VARCHAR(40)  NOT NULL,
    account_type    VARCHAR(20)  NOT NULL,         -- ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE
    owner_ref       VARCHAR(60)  NOT NULL,
    currency        VARCHAR(3)   NOT NULL,
    allow_negative  BOOLEAN      NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OPEN',   -- OPEN | FROZEN | CLOSED
    created_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uq_ledger_acct_code UNIQUE (code),
    CONSTRAINT chk_ledger_acct_ccy CHECK (currency IN ('USD','ZWG')),
    INDEX idx_ledger_acct_owner (category, owner_ref)
);

CREATE TABLE psp_journal_entries (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_ref           VARCHAR(40)  NOT NULL,
    idempotency_key     VARCHAR(190) NOT NULL,     -- business event key: COLLECT:<ref>, SETTLE_PAID:<ref>...
    entry_type          VARCHAR(40)  NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    description         VARCHAR(500),
    source_type         VARCHAR(40),
    source_ref          VARCHAR(120),
    reverses_entry_id   BIGINT,
    reverses_entry_ref  VARCHAR(40),
    effective_at        DATETIME(6)  NOT NULL,
    posted_by           VARCHAR(120) NOT NULL,
    entry_hash          VARCHAR(64)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    CONSTRAINT uq_journal_entry_ref UNIQUE (entry_ref),
    CONSTRAINT uq_journal_idem UNIQUE (idempotency_key),
    CONSTRAINT uq_journal_reverses UNIQUE (reverses_entry_id),   -- an entry can be reversed at most once
    CONSTRAINT fk_journal_reverses FOREIGN KEY (reverses_entry_id) REFERENCES psp_journal_entries(id),
    INDEX idx_journal_source (source_type, source_ref),
    INDEX idx_journal_type_time (entry_type, effective_at)
);

CREATE TABLE psp_journal_lines (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id      BIGINT       NOT NULL,
    line_no       INT          NOT NULL,
    account_id    BIGINT       NOT NULL,
    account_code  VARCHAR(120) NOT NULL,
    direction     VARCHAR(6)   NOT NULL,
    amount_minor  BIGINT       NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    memo          VARCHAR(200),
    CONSTRAINT fk_jline_entry FOREIGN KEY (entry_id) REFERENCES psp_journal_entries(id),
    CONSTRAINT fk_jline_account FOREIGN KEY (account_id) REFERENCES psp_ledger_accounts(id),
    CONSTRAINT uq_jline_entry_line UNIQUE (entry_id, line_no),
    CONSTRAINT chk_jline_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_jline_direction CHECK (direction IN ('DEBIT','CREDIT')),
    INDEX idx_jline_account (account_id, entry_id),
    INDEX idx_jline_entry (entry_id)
);

-- Running-total projection used for row locks and overdraft checks.
-- Re-derived from psp_journal_lines nightly by LedgerIntegrityService.
CREATE TABLE psp_account_balances (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id      BIGINT      NOT NULL,
    balance_minor   BIGINT      NOT NULL DEFAULT 0,
    debits_minor    BIGINT      NOT NULL DEFAULT 0,
    credits_minor   BIGINT      NOT NULL DEFAULT 0,
    last_entry_id   BIGINT,
    updated_at      DATETIME(6) NOT NULL,
    CONSTRAINT uq_balance_account UNIQUE (account_id),
    CONSTRAINT fk_balance_account FOREIGN KEY (account_id) REFERENCES psp_ledger_accounts(id)
);

CREATE TABLE psp_fund_holds (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    hold_ref                VARCHAR(120) NOT NULL,      -- the payment reference
    merchant_id             BIGINT       NOT NULL,
    currency                VARCHAR(3)   NOT NULL,
    amount_minor            BIGINT       NOT NULL,
    reserve_bps             INT          NOT NULL,
    reserve_days            INT          NOT NULL,
    release_at              DATETIME(6)  NOT NULL,
    status                  VARCHAR(20)  NOT NULL,      -- HELD | FROZEN | RELEASED
    released_at             DATETIME(6),
    released_amount_minor   BIGINT,
    reserve_minor           BIGINT,
    reserve_release_at      DATETIME(6),
    reserve_status          VARCHAR(20)  NOT NULL DEFAULT 'NONE',   -- NONE | HELD | RELEASED
    reserve_released_at     DATETIME(6),
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    CONSTRAINT uq_hold_ref UNIQUE (hold_ref),
    INDEX idx_hold_release (status, release_at),
    INDEX idx_hold_reserve (reserve_status, reserve_release_at),
    INDEX idx_hold_merchant (merchant_id, currency)
);

CREATE TRIGGER trg_journal_entries_no_update BEFORE UPDATE ON psp_journal_entries
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_journal_entries is append-only: post a reversal or adjustment';
CREATE TRIGGER trg_journal_entries_no_delete BEFORE DELETE ON psp_journal_entries
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_journal_entries is append-only: post a reversal or adjustment';
CREATE TRIGGER trg_journal_lines_no_update BEFORE UPDATE ON psp_journal_lines
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_journal_lines is append-only';
CREATE TRIGGER trg_journal_lines_no_delete BEFORE DELETE ON psp_journal_lines
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_journal_lines is append-only';
