-- =====================================================================
-- Phase 7: country and entity model, a second (sandbox) market, ISO 20022 messages.
--
-- Markets are configured in src/main/resources/markets/<CC>.json (MarketRegistry). Reference
-- rows here are data only: the ledger and FKs need the country, currency and legal entity to
-- exist, and the application user may only read ref_* (least privilege). A sandbox country
-- has ref_countries.enabled = FALSE, which MarketRegistry requires so it can never go live.
-- =====================================================================

INSERT INTO ref_currencies (code, name, minor_units, enabled) VALUES ('ZMW', 'Zambian Kwacha', 2, FALSE);
INSERT INTO ref_countries (code, name, enabled) VALUES ('ZM', 'Zambia', FALSE);
INSERT INTO ref_legal_entities (code, legal_name, country_code, base_currency, status)
VALUES ('PAYPORT_ZM_SANDBOX', 'PayPort Zambia - SANDBOX ONLY (no licence, not for launch)', 'ZM', 'ZMW', 'ACTIVE');

-- Structured payee for payout files (ISO 20022 needs the account, not a display string).
ALTER TABLE stl_items ADD COLUMN destination_id BIGINT NULL;

-- Every ISO 20022 message PayPort produced or accepted, with its schema validation result.
CREATE TABLE iso_messages (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    direction       VARCHAR(3)    NOT NULL,
    message_type    VARCHAR(30)   NOT NULL,
    msg_id          VARCHAR(35)   NOT NULL,
    related_ref     VARCHAR(64)   NULL,
    sha256          CHAR(64)      NOT NULL,
    schema_valid    BOOLEAN       NOT NULL,
    content         MEDIUMTEXT    NOT NULL,
    created_by      VARCHAR(120)  NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    CONSTRAINT chk_isom_dir CHECK (direction IN ('IN','OUT')),
    CONSTRAINT chk_isom_valid CHECK (schema_valid = TRUE),
    CONSTRAINT uq_isom UNIQUE (direction, message_type, sha256),
    INDEX idx_isom_msg (message_type, msg_id)
);

-- Bank statements (camt.053) for PayPort's own bank accounts, and how each entry was traced.
CREATE TABLE bank_statements (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    iso_message_id    BIGINT        NOT NULL,
    country           CHAR(2)       NOT NULL,
    entity_code       VARCHAR(30)   NOT NULL,
    account_id        VARCHAR(64)   NOT NULL,
    currency          CHAR(3)       NOT NULL,
    statement_id      VARCHAR(35)   NOT NULL,
    from_at           DATETIME(6)   NULL,
    to_at             DATETIME(6)   NULL,
    opening_minor     BIGINT        NOT NULL,
    closing_minor     BIGINT        NOT NULL,
    credits_minor     BIGINT        NOT NULL,
    debits_minor      BIGINT        NOT NULL,
    entry_count       INT           NOT NULL,
    imported_by       VARCHAR(120)  NOT NULL,
    imported_at       DATETIME(6)   NOT NULL,
    CONSTRAINT fk_bst_msg FOREIGN KEY (iso_message_id) REFERENCES iso_messages (id),
    CONSTRAINT fk_bst_entity FOREIGN KEY (entity_code) REFERENCES ref_legal_entities (code),
    CONSTRAINT chk_bst_balance CHECK (opening_minor + credits_minor - debits_minor = closing_minor),
    CONSTRAINT uq_bst UNIQUE (account_id, currency, statement_id)
);

CREATE TABLE bank_statement_entries (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    statement_id      BIGINT        NOT NULL,
    entry_no          INT           NOT NULL,
    direction         VARCHAR(4)    NOT NULL,
    amount_minor      BIGINT        NOT NULL,
    currency          CHAR(3)       NOT NULL,
    status            VARCHAR(4)    NOT NULL,
    booking_date      DATE          NULL,
    value_date        DATE          NULL,
    bank_reference    VARCHAR(64)   NULL,
    end_to_end_id     VARCHAR(64)   NULL,
    counterparty      VARCHAR(140)  NULL,
    remittance        VARCHAR(500)  NULL,
    trace_status      VARCHAR(30)   NOT NULL,
    stl_item_id       BIGINT        NULL,
    ledger_entry_ref  VARCHAR(64)   NULL,
    detail            VARCHAR(500)  NULL,
    CONSTRAINT fk_bse_stmt FOREIGN KEY (statement_id) REFERENCES bank_statements (id),
    CONSTRAINT chk_bse_dir CHECK (direction IN ('CRDT','DBIT')),
    CONSTRAINT chk_bse_trace CHECK (trace_status IN ('TRACED','NOT_YET_CONFIRMED','AMOUNT_MISMATCH','UNIDENTIFIED','NOT_BOOKED','LEDGER_PENDING')),
    CONSTRAINT uq_bse UNIQUE (statement_id, entry_no),
    INDEX idx_bse_e2e (end_to_end_id)
);

-- Payout ledger postings (STL_PAYOUT_PAID / STL_PAYOUT_RETURNED), so a bank entry can be traced to the books.
CREATE TABLE stl_item_ledger (
    stl_item_id       BIGINT        NOT NULL,
    event             VARCHAR(10)   NOT NULL,
    entry_ref         VARCHAR(64)   NULL,
    status            VARCHAR(10)   NOT NULL,
    error             VARCHAR(500)  NULL,
    updated_at        DATETIME(6)   NOT NULL,
    PRIMARY KEY (stl_item_id, event),
    CONSTRAINT chk_sil_event CHECK (event IN ('PAID','RETURNED')),
    CONSTRAINT chk_sil_status CHECK (status IN ('POSTED','FAILED'))
);

-- 2027 Zimbabwe public holidays (Sunday holidays observed on the Monday; Easter Sunday 2027 is 28 March).
-- PROVISIONAL like the 2026 dates: check against the Government Gazette.
UPDATE compliance_parameters
   SET value_json = JSON_MERGE_PRESERVE(value_json, JSON_ARRAY('2027-01-01','2027-02-22','2027-03-26','2027-03-27','2027-03-29','2027-04-19',
                                                              '2027-05-01','2027-05-25','2027-08-09','2027-08-10','2027-12-22','2027-12-25','2027-12-27')),
       version = version + 1, updated_by = 'MIGRATION:V49', updated_at = NOW(6)
 WHERE param_key = 'CALENDAR.ZW.HOLIDAYS';
