-- =====================================================================
-- Push notifications (owner decision 2026-10-16: yes). Free, unlike SMS, so customers are told the
-- final outcome of every payment by push: success, failure, timeout, refund. SMS stays success-only.
-- =====================================================================
CREATE TABLE push_tokens (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    token           VARCHAR(200)  NOT NULL,
    platform        VARCHAR(10)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    last_seen_at    DATETIME(6)   NOT NULL,
    disabled_at     DATETIME(6)   NULL,
    disabled_reason VARCHAR(100)  NULL,
    CONSTRAINT fk_pt_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_pt_token UNIQUE (token),
    INDEX idx_pt_user (user_id, disabled_at)
);

-- Outbox: written in the same database transaction as the status change, sent afterwards by a worker,
-- so a push is never sent for a change that rolled back and never lost if the process stops.
CREATE TABLE push_outbox (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    transaction_reference VARCHAR(40)   NOT NULL,
    status                VARCHAR(30)   NOT NULL,
    user_id               BIGINT        NOT NULL,
    state                 VARCHAR(10)   NOT NULL,
    attempts              INT           NOT NULL DEFAULT 0,
    next_attempt_at       DATETIME(6)   NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    sent_at               DATETIME(6)   NULL,
    last_error            VARCHAR(500)  NULL,
    CONSTRAINT uq_po_event UNIQUE (transaction_reference, status),
    CONSTRAINT chk_po_state CHECK (state IN ('PENDING','SENT','FAILED','SKIPPED')),
    INDEX idx_po_due (state, next_attempt_at)
);
