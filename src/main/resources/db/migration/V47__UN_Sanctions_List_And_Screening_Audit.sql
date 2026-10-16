-- =====================================================================
-- UN Security Council Consolidated List: automatic, verified updates; auditable screening.
-- =====================================================================
ALTER TABLE screening_lists
    ADD COLUMN list_kind VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN required_for_clear BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN source_url VARCHAR(500) NULL,
    ADD COLUMN source_sha256 CHAR(64) NULL,
    ADD COLUMN source_generated_at VARCHAR(40) NULL,
    ADD COLUMN last_checked_at DATETIME(6) NULL,
    ADD COLUMN last_check_status VARCHAR(12) NULL,
    ADD COLUMN last_error VARCHAR(500) NULL;

ALTER TABLE screening_entries ADD COLUMN details TEXT NULL;

INSERT INTO screening_lists (code, name, source, version, entry_count, status, created_by, created_at, list_kind, required_for_clear, source_url)
VALUES ('UN-CONSOLIDATED', 'United Nations Security Council Consolidated List',
        'UN Security Council (scsanctions.un.org); obligations: UNSC resolutions, SI 76 of 2014, SI 110 of 2021, RBZ Guideline 01-2025 s3.80-3.83',
        0, 0, 'ACTIVE', 'MIGRATION:V47', NOW(6), 'UN_AUTO', TRUE, 'https://scsanctions.un.org/resources/xml/en/consolidated.xml');

-- Every download attempt (evidence for examiners: when we checked, what we got, what we did).
CREATE TABLE screening_list_fetches (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    list_code       VARCHAR(40)   NOT NULL,
    at              DATETIME(6)   NOT NULL,
    outcome         VARCHAR(12)   NOT NULL,
    final_url_host  VARCHAR(200)  NULL,
    bytes           BIGINT        NULL,
    sha256          CHAR(64)      NULL,
    generated_at    VARCHAR(40)   NULL,
    entries         INT           NULL,
    activated_version INT         NULL,
    error           VARCHAR(500)  NULL,
    CONSTRAINT chk_slf_outcome CHECK (outcome IN ('NEW_VERSION','UNCHANGED','FAILED','QUARANTINED')),
    INDEX idx_slf_list (list_code, at)
);

-- Every screening performed (who/what was screened, against which list versions, with what result).
CREATE TABLE screening_runs (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    at             DATETIME(6)   NOT NULL,
    subject_key    VARCHAR(160)  NOT NULL,
    context        VARCHAR(20)   NOT NULL,
    outcome        VARCHAR(16)   NOT NULL,
    min_score      DECIMAL(5,3)  NOT NULL,
    list_versions  VARCHAR(1000) NOT NULL,
    incomplete_reason VARCHAR(500) NULL,
    matches        TEXT          NULL,
    CONSTRAINT chk_sr_outcome CHECK (outcome IN ('CLEAR','POTENTIAL_MATCH','INCOMPLETE')),
    INDEX idx_sr_subject (subject_key, at)
);

INSERT INTO compliance_parameters (param_key, category, value_json, unit, description, source, status, updated_by, updated_at) VALUES
('SCREENING.INCOMPLETE_PAYMENT_ACTION', 'SCREENING', '"ALERT"', 'ALERT|BLOCK',
 'When the required lists are unavailable or outdated, a payment is never treated as cleared; ALERT lets it proceed with a compliance alert, BLOCK refuses it (BLOCK applies only while the sanctions rule is approved and enforced)',
 'PayPort control for RBZ Guideline 01-2025 s3.80-3.83; choose with compliance', 'PROVISIONAL', 'MIGRATION:V47', NOW(6));

INSERT INTO risk_rules (code, name, rule_type, params, mode, action, severity, updated_by, updated_at, source, param_status) VALUES
('SCREENING_UNAVAILABLE', 'Screening could not be completed (list unavailable or outdated)', 'SCREENING_HEALTH', '{}', 'OBSERVE', 'ALERT', 'HIGH',
 'MIGRATION:V47', NOW(6), 'RBZ Guideline 01-2025 s3.83 (lists updated immediately on designation)', 'SOURCED');
