-- =====================================================================
-- Configurable FIU report templates (versioned; a new version becomes ACTIVE only with a second
-- compliance officer's approval). Reports stay DRAFT until an authorised REPORT_APPROVER approves them.
-- =====================================================================
CREATE TABLE fiu_report_templates (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(40)   NOT NULL,
    report_type   VARCHAR(5)    NOT NULL,
    version       INT           NOT NULL,
    status        VARCHAR(10)   NOT NULL,
    output_format VARCHAR(12)   NOT NULL,
    definition    MEDIUMTEXT    NOT NULL,
    source        VARCHAR(600)  NOT NULL,
    created_by    VARCHAR(120)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    approved_by   VARCHAR(250)  NULL,
    activated_at  DATETIME(6)   NULL,
    CONSTRAINT uq_frt UNIQUE (code, version),
    CONSTRAINT chk_frt_type CHECK (report_type IN ('STR','CTR')),
    CONSTRAINT chk_frt_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT chk_frt_format CHECK (output_format IN ('GOAML_XML','JSON')),
    CONSTRAINT chk_frt_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by)
);

ALTER TABLE fiu_reports
    ADD COLUMN template_id BIGINT NULL,
    ADD COLUMN fields_json MEDIUMTEXT NULL,
    ADD COLUMN rendered MEDIUMTEXT NULL,
    ADD COLUMN missing_fields VARCHAR(2000) NULL,
    ADD COLUMN suspicion_formed_at DATETIME(6) NULL,
    ADD COLUMN due_at DATETIME(6) NULL;

INSERT INTO compliance_parameters (param_key, category, value_json, unit, description, source, status, updated_by, updated_at) VALUES
('FIU.REPORTING_ENTITY_ID', 'AML', '""', 'goAML ID', 'PayPort''s goAML reporting entity ID (issued on goAML registration)', 'RBZ Guideline 01-2025 s3.106: reporting institutions must be registered on goAML', 'PROVISIONAL', 'MIGRATION:V48', NOW(6)),
('FIU.REPORTING_ENTITY_NAME', 'AML', '"PayPort (Private) Limited"', 'text', 'Registered name of the reporting institution', 'Company registration', 'PROVISIONAL', 'MIGRATION:V48', NOW(6)),
('FIU.REPORTING_ENTITY_BRANCH', 'AML', '"Head Office, Harare"', 'text', 'Reporting office', 'PayPort', 'PROVISIONAL', 'MIGRATION:V48', NOW(6));
