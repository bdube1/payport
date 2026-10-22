-- =====================================================================
-- Phase 1: platform services.
--   - Job leases + run history: scheduled financial work runs on exactly one instance at a
--     time and leaves a record (PHASE1_DESIGN.md; target architecture section 8).
--   - Maker-checker approvals: the database itself refuses self-approval.
--   - Tax rates: effective-dated; active only after a second person approves. Nothing seeded.
-- =====================================================================

CREATE TABLE platform_job_leases (
    job_name     VARCHAR(80)  NOT NULL PRIMARY KEY,
    owner        VARCHAR(120) NOT NULL,
    lease_until  DATETIME(6)  NOT NULL,
    acquired_at  DATETIME(6)  NULL
);

CREATE TABLE platform_job_runs (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_name     VARCHAR(80)   NOT NULL,
    owner        VARCHAR(120)  NOT NULL,
    started_at   DATETIME(6)   NOT NULL,
    finished_at  DATETIME(6)   NULL,
    status       VARCHAR(10)   NOT NULL,
    summary      VARCHAR(2000) NULL,
    error        VARCHAR(2000) NULL,
    CONSTRAINT chk_pjr_status CHECK (status IN ('RUNNING','SUCCEEDED','FAILED')),
    INDEX idx_pjr_job_time (job_name, started_at)
);

CREATE TABLE platform_approvals (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    approval_ref   VARCHAR(40)   NOT NULL,
    action_type    VARCHAR(60)   NOT NULL,
    subject_ref    VARCHAR(120)  NOT NULL,
    payload        TEXT          NOT NULL,
    reason         VARCHAR(1000) NOT NULL,
    required_role  VARCHAR(30)   NOT NULL,
    status         VARCHAR(10)   NOT NULL,
    requested_by   VARCHAR(120)  NOT NULL,
    requested_at   DATETIME(6)   NOT NULL,
    decided_by     VARCHAR(120)  NULL,
    decided_at     DATETIME(6)   NULL,
    decision_note  VARCHAR(1000) NULL,
    expires_at     DATETIME(6)   NOT NULL,
    CONSTRAINT uq_papp_ref UNIQUE (approval_ref),
    CONSTRAINT chk_papp_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED','EXPIRED')),
    -- Four eyes, enforced by the database: nobody approves or rejects their own request.
    CONSTRAINT chk_papp_four_eyes CHECK (status NOT IN ('APPROVED','REJECTED')
                                         OR (decided_by IS NOT NULL AND decided_by <> requested_by)),
    INDEX idx_papp_status (status, requested_at)
);

CREATE TABLE fin_tax_rates (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    entity_code     VARCHAR(30)  NOT NULL,
    tax_code        VARCHAR(20)  NOT NULL,
    currency        CHAR(3)      NOT NULL,
    rate_bps        INT          NOT NULL,
    cap_minor       BIGINT       NULL,
    merchant_borne  BOOLEAN      NOT NULL DEFAULT FALSE,
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NULL,
    status          VARCHAR(10)  NOT NULL,
    approval_id     BIGINT       NOT NULL,
    created_by      VARCHAR(120) NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    approved_by     VARCHAR(120) NULL,
    approved_at     DATETIME(6)  NULL,
    notes           VARCHAR(1000) NULL,
    CONSTRAINT fk_ftr_entity   FOREIGN KEY (entity_code) REFERENCES ref_legal_entities (code),
    CONSTRAINT fk_ftr_ccy      FOREIGN KEY (currency)    REFERENCES ref_currencies (code),
    CONSTRAINT fk_ftr_approval FOREIGN KEY (approval_id) REFERENCES platform_approvals (id),
    CONSTRAINT chk_ftr_code    CHECK (tax_code IN ('VAT','IMTT')),
    CONSTRAINT chk_ftr_rate    CHECK (rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT chk_ftr_cap     CHECK (cap_minor IS NULL OR cap_minor > 0),
    CONSTRAINT chk_ftr_dates   CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_ftr_status  CHECK (status IN ('PROPOSED','ACTIVE','REJECTED')),
    CONSTRAINT chk_ftr_four_eyes CHECK (status <> 'ACTIVE' OR (approved_by IS NOT NULL AND approved_by <> created_by)),
    INDEX idx_ftr_lookup (entity_code, tax_code, currency, status, effective_from)
);
