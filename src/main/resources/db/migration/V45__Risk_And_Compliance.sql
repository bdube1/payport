-- =====================================================================
-- Phase 6: risk and compliance.
-- Rules start in OBSERVE (alert only). Moving a rule to ENFORCE, or changing its thresholds, takes
-- two compliance officers (platform_approvals, required_role COMPLIANCE). Every threshold seeded here
-- is PROVISIONAL until compliance confirms it against the RBZ / FIU requirements.
-- =====================================================================

CREATE TABLE risk_rules (
    code        VARCHAR(40)   NOT NULL PRIMARY KEY,
    name        VARCHAR(120)  NOT NULL,
    rule_type   VARCHAR(30)   NOT NULL,
    params      TEXT          NOT NULL,
    mode        VARCHAR(10)   NOT NULL,
    action      VARCHAR(10)   NOT NULL,
    severity    VARCHAR(10)   NOT NULL,
    version     INT           NOT NULL DEFAULT 1,
    updated_by  VARCHAR(120)  NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    CONSTRAINT chk_rr_mode CHECK (mode IN ('OFF','OBSERVE','ENFORCE')),
    CONSTRAINT chk_rr_action CHECK (action IN ('ALERT','BLOCK')),
    CONSTRAINT chk_rr_sev CHECK (severity IN ('LOW','MEDIUM','HIGH'))
);

INSERT INTO risk_rules (code, name, rule_type, params, mode, action, severity, updated_by, updated_at) VALUES
('VELOCITY_COUNT_1H', 'Many payments from one phone in an hour', 'VELOCITY_COUNT',
 '{"windowMinutes":60,"maxCount":10}', 'OBSERVE', 'BLOCK', 'MEDIUM', 'MIGRATION:V45', NOW(6)),
('VELOCITY_AMOUNT_24H', 'High value from one phone in a day', 'VELOCITY_AMOUNT',
 '{"windowMinutes":1440,"limitMinor":{"USD":200000,"ZWG":5000000}}', 'OBSERVE', 'BLOCK', 'MEDIUM', 'MIGRATION:V45', NOW(6)),
('STRUCTURING_24H', 'Repeated payments just under the reporting threshold', 'STRUCTURING',
 '{"windowMinutes":1440,"thresholdMinor":{"USD":100000,"ZWG":2500000},"lowerRatio":0.8,"minCount":3}', 'OBSERVE', 'ALERT', 'HIGH', 'MIGRATION:V45', NOW(6)),
('LARGE_TRANSACTION', 'At or above the reporting threshold (CTR candidate)', 'LARGE_TRANSACTION',
 '{"thresholdMinor":{"USD":100000,"ZWG":2500000}}', 'OBSERVE', 'ALERT', 'LOW', 'MIGRATION:V45', NOW(6)),
('SANCTIONS_CUSTOMER', 'Customer matches a screening list', 'SANCTIONS',
 '{"minScore":0.88}', 'OBSERVE', 'BLOCK', 'HIGH', 'MIGRATION:V45', NOW(6));

CREATE TABLE screening_lists (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(40)   NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    source      VARCHAR(200)  NOT NULL,
    version     INT           NOT NULL DEFAULT 0,
    entry_count INT           NOT NULL DEFAULT 0,
    status      VARCHAR(10)   NOT NULL,
    loaded_by   VARCHAR(120)  NULL,
    loaded_at   DATETIME(6)   NULL,
    created_by  VARCHAR(120)  NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    CONSTRAINT uq_sl_code UNIQUE (code),
    CONSTRAINT chk_sl_status CHECK (status IN ('ACTIVE','RETIRED'))
);

CREATE TABLE screening_entries (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    list_id         BIGINT        NOT NULL,
    list_version    INT           NOT NULL,
    entry_ref       VARCHAR(80)   NOT NULL,
    entity_type     VARCHAR(10)   NOT NULL,
    name            VARCHAR(300)  NOT NULL,
    aliases         TEXT          NULL,
    identifiers     VARCHAR(500)  NULL,
    country         VARCHAR(2)    NULL,
    CONSTRAINT fk_se_list FOREIGN KEY (list_id) REFERENCES screening_lists (id),
    CONSTRAINT chk_se_type CHECK (entity_type IN ('PERSON','ORG')),
    INDEX idx_se_list (list_id, list_version)
);

-- A person/business cleared for a specific list entry (false positive): not alerted again for it.
CREATE TABLE screening_suppressions (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_key  VARCHAR(160)  NOT NULL,
    list_code    VARCHAR(40)   NOT NULL,
    entry_ref    VARCHAR(80)   NOT NULL,
    case_id      BIGINT        NOT NULL,
    decided_by   VARCHAR(120)  NOT NULL,
    decided_at   DATETIME(6)   NOT NULL,
    CONSTRAINT uq_ss UNIQUE (subject_key, list_code, entry_ref)
);

CREATE TABLE risk_cases (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_ref          VARCHAR(30)   NOT NULL,
    subject_type      VARCHAR(12)   NOT NULL,
    subject_key       VARCHAR(160)  NOT NULL,
    subject_name      VARCHAR(300)  NULL,
    status            VARCHAR(25)   NOT NULL,
    priority          VARCHAR(10)   NOT NULL,
    assignee          VARCHAR(120)  NULL,
    opened_at         DATETIME(6)   NOT NULL,
    sla_due_at        DATETIME(6)   NOT NULL,
    proposed_outcome  VARCHAR(25)   NULL,
    proposed_by       VARCHAR(120)  NULL,
    proposed_at       DATETIME(6)   NULL,
    proposal_note     VARCHAR(2000) NULL,
    decided_by        VARCHAR(120)  NULL,
    decided_at        DATETIME(6)   NULL,
    CONSTRAINT uq_rc_ref UNIQUE (case_ref),
    CONSTRAINT chk_rc_status CHECK (status IN ('OPEN','INVESTIGATING','PENDING_DECISION','CLOSED_FALSE_POSITIVE','CLOSED_NO_ACTION','CLOSED_CONFIRMED')),
    CONSTRAINT chk_rc_four_eyes CHECK (decided_by IS NULL OR (proposed_by IS NOT NULL AND decided_by <> proposed_by)),
    INDEX idx_rc_subject (subject_type, subject_key, status),
    INDEX idx_rc_status (status, sla_due_at)
);

CREATE TABLE risk_alerts (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dedupe_key            VARCHAR(250)  NOT NULL,
    rule_code             VARCHAR(40)   NOT NULL,
    rule_version          INT           NOT NULL,
    mode                  VARCHAR(10)   NOT NULL,
    severity              VARCHAR(10)   NOT NULL,
    subject_type          VARCHAR(12)   NOT NULL,
    subject_key           VARCHAR(160)  NOT NULL,
    subject_name          VARCHAR(300)  NULL,
    transaction_reference VARCHAR(40)   NULL,
    score                 DECIMAL(5,3)  NULL,
    details               TEXT          NULL,
    action_taken          VARCHAR(10)   NOT NULL,
    hits                  INT           NOT NULL DEFAULT 1,
    status                VARCHAR(10)   NOT NULL,
    case_id               BIGINT        NULL,
    created_at            DATETIME(6)   NOT NULL,
    last_hit_at           DATETIME(6)   NOT NULL,
    CONSTRAINT uq_ra_dedupe UNIQUE (dedupe_key),
    CONSTRAINT chk_ra_action CHECK (action_taken IN ('ALERT','BLOCKED')),
    CONSTRAINT chk_ra_status CHECK (status IN ('OPEN','IN_CASE','CLOSED')),
    INDEX idx_ra_status (status, created_at),
    INDEX idx_ra_subject (subject_key)
);

CREATE TABLE risk_case_notes (
    id         BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id    BIGINT         NOT NULL,
    author     VARCHAR(120)   NOT NULL,
    note       VARCHAR(4000)  NOT NULL,
    created_at DATETIME(6)    NOT NULL,
    CONSTRAINT fk_rcn_case FOREIGN KEY (case_id) REFERENCES risk_cases (id),
    INDEX idx_rcn_case (case_id)
);

-- Reports to the Financial Intelligence Unit. The FIU's submission format is not yet confirmed, so
-- reports are produced in a provisional, documented structure (format_version) and the MLRO files
-- them manually, recording the FIU's reference. Preparer and approver must differ.
CREATE TABLE fiu_reports (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_ref      VARCHAR(30)   NOT NULL,
    report_type     VARCHAR(5)    NOT NULL,
    status          VARCHAR(10)   NOT NULL,
    case_id         BIGINT        NULL,
    period_from     DATETIME(6)   NULL,
    period_to       DATETIME(6)   NULL,
    format_version  VARCHAR(40)   NOT NULL,
    payload         MEDIUMTEXT    NOT NULL,
    prepared_by     VARCHAR(120)  NOT NULL,
    prepared_at     DATETIME(6)   NOT NULL,
    approved_by     VARCHAR(120)  NULL,
    approved_at     DATETIME(6)   NULL,
    submitted_by    VARCHAR(120)  NULL,
    submitted_at    DATETIME(6)   NULL,
    fiu_reference   VARCHAR(100)  NULL,
    CONSTRAINT uq_fr_ref UNIQUE (report_ref),
    CONSTRAINT chk_fr_type CHECK (report_type IN ('STR','CTR')),
    CONSTRAINT chk_fr_status CHECK (status IN ('DRAFT','APPROVED','SUBMITTED')),
    CONSTRAINT chk_fr_four_eyes CHECK (approved_by IS NULL OR approved_by <> prepared_by)
);

-- Dispute timelines. Deadlines come from configuration (payport.disputes.sla.*); the values must be
-- confirmed against the RBZ rules before go-live.
ALTER TABLE disputes
    ADD COLUMN acknowledged_at DATETIME NULL,
    ADD COLUMN acknowledged_by VARCHAR(120) NULL,
    ADD COLUMN ack_due_at DATETIME NULL,
    ADD COLUMN resolve_due_at DATETIME NULL;
