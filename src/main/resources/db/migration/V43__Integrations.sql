-- =====================================================================
-- Phase 5: provider integrations.
-- =====================================================================

-- Operations kill switch per provider. A row without resumed_at means the provider is suspended:
-- every instance refuses new payments/deliveries to it before anything is sent.
CREATE TABLE provider_suspensions (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_code VARCHAR(30)   NOT NULL,
    reason        VARCHAR(500)  NOT NULL,
    suspended_by  VARCHAR(120)  NOT NULL,
    suspended_at  DATETIME(6)   NOT NULL,
    resumed_by    VARCHAR(120)  NULL,
    resumed_at    DATETIME(6)   NULL,
    resume_note   VARCHAR(500)  NULL,
    INDEX idx_ps_open (provider_code, resumed_at)
);

-- Evidence for the integration register: each verification run against a provider environment
-- (contract tests on fixtures, sandbox runs, production smoke tests) with its result.
CREATE TABLE provider_verifications (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_code VARCHAR(30)   NOT NULL,
    environment   VARCHAR(20)   NOT NULL,
    flow          VARCHAR(60)   NOT NULL,
    result        VARCHAR(10)   NOT NULL,
    evidence      VARCHAR(1000) NULL,
    recorded_by   VARCHAR(120)  NOT NULL,
    recorded_at   DATETIME(6)   NOT NULL,
    CONSTRAINT chk_pv_env CHECK (environment IN ('SPEC_FIXTURE','SANDBOX','UAT','PRODUCTION')),
    CONSTRAINT chk_pv_result CHECK (result IN ('PASS','FAIL')),
    INDEX idx_pv_provider (provider_code, recorded_at)
);
