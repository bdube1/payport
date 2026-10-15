-- =====================================================================
-- Phase 1: shadow-ledger bookkeeping (ADR-0009). Application-writable (not ledger_* tables).
-- =====================================================================

-- How far each source stream has been scanned.
CREATE TABLE shadow_ledger_state (
    stream        VARCHAR(40)  NOT NULL PRIMARY KEY,
    watermark     DATETIME(6)  NOT NULL,
    last_run_at   DATETIME(6)  NULL,
    last_posted   INT          NOT NULL DEFAULT 0,
    last_failed   INT          NOT NULL DEFAULT 0,
    updated_at    DATETIME(6)  NOT NULL
);

-- Records that could not be posted. Retried every run until they post or are resolved by a person.
CREATE TABLE shadow_ledger_failures (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    idempotency_key VARCHAR(190) NOT NULL,
    template_code   VARCHAR(60)  NOT NULL,
    source_type     VARCHAR(40)  NOT NULL,
    source_ref      VARCHAR(120) NOT NULL,
    error_code      VARCHAR(60)  NOT NULL,
    message         VARCHAR(1000) NOT NULL,
    attempts        INT          NOT NULL DEFAULT 1,
    first_seen_at   DATETIME(6)  NOT NULL,
    last_seen_at    DATETIME(6)  NOT NULL,
    resolved_at     DATETIME(6)  NULL,
    resolution      VARCHAR(500) NULL,
    resolved_by     VARCHAR(120) NULL,
    CONSTRAINT uq_slf_key UNIQUE (idempotency_key),
    INDEX idx_slf_open (resolved_at, last_seen_at)
);
