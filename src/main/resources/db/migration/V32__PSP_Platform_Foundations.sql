-- =====================================================================
-- PSP platform foundations: event bus (outbox), idempotency keys,
-- maker-checker approvals, tamper-evident audit trail, hash-chain seals.
--
-- NOTE ON TRIGGERS: creating triggers with binary logging enabled needs
-- either SUPER/SYSTEM_VARIABLES_ADMIN for the Flyway user or
-- log_bin_trust_function_creators=1 on the server. See docs/PSP_ROLLOUT.md.
-- =====================================================================

CREATE TABLE psp_outbox_events (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id         VARCHAR(40)   NOT NULL,
    event_type       VARCHAR(80)   NOT NULL,
    aggregate_type   VARCHAR(60)   NOT NULL,
    aggregate_id     VARCHAR(120)  NOT NULL,
    payload          TEXT          NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'NEW',  -- NEW | RETRY | PUBLISHED | DEAD
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(6)   NOT NULL,
    locked_by        VARCHAR(80),
    locked_until     DATETIME(6),
    last_error       VARCHAR(2000),
    published_at     DATETIME(6),
    created_at       DATETIME(6)   NOT NULL,
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    INDEX idx_outbox_dispatch (status, next_attempt_at),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id)
);

CREATE TABLE psp_outbox_deliveries (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id      VARCHAR(40)  NOT NULL,
    handler       VARCHAR(120) NOT NULL,
    delivered_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uq_outbox_delivery UNIQUE (event_id, handler)
);

CREATE TABLE psp_idempotency_keys (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope            VARCHAR(120) NOT NULL,     -- merchant:42, partner:acme, wallet:+2637...
    idem_key         VARCHAR(120) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,     -- IN_PROGRESS | COMPLETED
    response_status  INT,
    response_body    TEXT,
    resource_ref     VARCHAR(120),
    expires_at       DATETIME(6)  NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    CONSTRAINT uq_idem_scope_key UNIQUE (scope, idem_key),
    INDEX idx_idem_expiry (expires_at)
);

CREATE TABLE psp_approval_requests (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    approval_ref      VARCHAR(40)   NOT NULL,
    action_type       VARCHAR(60)   NOT NULL,
    target_ref        VARCHAR(120)  NOT NULL,
    payload           TEXT          NOT NULL,
    summary           VARCHAR(500)  NOT NULL,
    status            VARCHAR(20)   NOT NULL,   -- PENDING | EXECUTED | REJECTED | CANCELLED | EXPIRED
    required_role     VARCHAR(40)   NOT NULL,
    requested_by      VARCHAR(120)  NOT NULL,
    requested_at      DATETIME(6)   NOT NULL,
    reason            VARCHAR(1000),
    decided_by        VARCHAR(120),
    decided_at        DATETIME(6),
    decision_note     VARCHAR(1000),
    execution_result  VARCHAR(2000),
    expires_at        DATETIME(6)   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_approval_ref UNIQUE (approval_ref),
    CONSTRAINT chk_four_eyes CHECK (decided_by IS NULL OR status IN ('CANCELLED','EXPIRED') OR decided_by <> requested_by),
    INDEX idx_approval_status (status, requested_at),
    INDEX idx_approval_target (action_type, target_ref)
);

CREATE TABLE psp_audit_events (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_ref     VARCHAR(40)  NOT NULL,
    actor         VARCHAR(120) NOT NULL,
    actor_roles   VARCHAR(300),
    action        VARCHAR(80)  NOT NULL,
    object_type   VARCHAR(60)  NOT NULL,
    object_ref    VARCHAR(120) NOT NULL,
    details       TEXT,
    ip_address    VARCHAR(64),
    request_id    VARCHAR(80),
    content_hash  VARCHAR(64)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_audit_event_ref UNIQUE (event_ref),
    INDEX idx_audit_object (object_type, object_ref),
    INDEX idx_audit_actor (actor, created_at)
);

CREATE TABLE psp_chain_seals (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    chain_name      VARCHAR(20) NOT NULL,       -- LEDGER | AUDIT
    from_id         BIGINT      NOT NULL,
    to_id           BIGINT      NOT NULL,
    row_count       INT         NOT NULL,
    seal_hash       VARCHAR(64) NOT NULL,
    prev_seal_hash  VARCHAR(64) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT uq_seal_chain_to UNIQUE (chain_name, to_id)
);

-- Append-only enforcement. Application code never updates or deletes these
-- rows; the triggers make sure nobody else does either (including a DBA
-- running an ad-hoc statement). Corrections are new rows.
CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON psp_audit_events
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_audit_events is append-only';
CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON psp_audit_events
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_audit_events is append-only';
CREATE TRIGGER trg_seal_no_update BEFORE UPDATE ON psp_chain_seals
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_chain_seals is append-only';
CREATE TRIGGER trg_seal_no_delete BEFORE DELETE ON psp_chain_seals
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'psp_chain_seals is append-only';
