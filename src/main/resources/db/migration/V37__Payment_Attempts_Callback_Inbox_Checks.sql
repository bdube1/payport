-- =====================================================================
-- Phase 2, slice 2.1: payment lifecycle durability.
--
-- payment_attempts  one row per request sent to a provider, with its classified outcome.
--                   ACCEPTED / REJECTED are definite; UNKNOWN means "may have reached the
--                   provider": the payment stays pending and is resolved by status query,
--                   never by sending another request.
-- payment_callbacks every provider callback, stored BEFORE processing (an inbox), with
--                   duplicates counted and the processing outcome recorded.
-- payment_checks    the uncertain-outcome resolver's schedule, durable across restarts
--                   and shared by all instances (replaces an in-memory map).
-- =====================================================================

CREATE TABLE payment_attempts (
    id                     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    transaction_reference  VARCHAR(100)  NOT NULL,
    attempt_no             INT           NOT NULL,
    provider_code          VARCHAR(30)   NOT NULL,
    status                 VARCHAR(10)   NOT NULL,
    provider_reference     VARCHAR(200)  NULL,
    error_class            VARCHAR(200)  NULL,
    error_message          VARCHAR(1000) NULL,
    sent_at                DATETIME(6)   NOT NULL,
    responded_at           DATETIME(6)   NULL,
    CONSTRAINT uq_pattempt UNIQUE (transaction_reference, attempt_no),
    CONSTRAINT chk_pattempt_status CHECK (status IN ('SENT','ACCEPTED','REJECTED','UNKNOWN')),
    INDEX idx_pattempt_status (status, sent_at)
);

CREATE TABLE payment_callbacks (
    id                   BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_code        VARCHAR(30)   NOT NULL,
    body_sha256          CHAR(64)      NOT NULL,
    raw_body             MEDIUMTEXT    NOT NULL,
    remote_ip            VARCHAR(64)   NULL,
    payport_reference    VARCHAR(100)  NULL,
    claimed_status       VARCHAR(20)   NULL,
    status               VARCHAR(20)   NOT NULL,
    outcome_detail       VARCHAR(1000) NULL,
    received_count       INT           NOT NULL DEFAULT 1,
    first_received_at    DATETIME(6)   NOT NULL,
    last_received_at     DATETIME(6)   NOT NULL,
    processed_at         DATETIME(6)   NULL,
    CONSTRAINT uq_pcallback UNIQUE (provider_code, body_sha256),
    CONSTRAINT chk_pcallback_status CHECK (status IN ('RECEIVED','APPLIED','NO_CHANGE','CONFLICT','INVALID','UNKNOWN_REF','ERROR')),
    INDEX idx_pcallback_ref (payport_reference),
    INDEX idx_pcallback_status (status, last_received_at)
);

CREATE TABLE payment_checks (
    transaction_reference  VARCHAR(100)  NOT NULL PRIMARY KEY,
    provider_code          VARCHAR(30)   NOT NULL,
    status                 VARCHAR(10)   NOT NULL,
    submitted_at           DATETIME(6)   NOT NULL,
    next_check_at          DATETIME(6)   NOT NULL,
    check_count            INT           NOT NULL DEFAULT 0,
    last_checked_at        DATETIME(6)   NULL,
    last_result            VARCHAR(500)  NULL,
    closed_at              DATETIME(6)   NULL,
    CONSTRAINT chk_pcheck_status CHECK (status IN ('OPEN','RESOLVED','MANUAL')),
    INDEX idx_pcheck_due (status, next_check_at)
);
