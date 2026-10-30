-- Customer care (owner, 2026-10-30): customer, merchant and agent complaints get a reference, an owner and a deadline,
-- and every reversal is requested and approved in one place (maker-checker) - the only way staff reverse money.

CREATE TABLE complaints (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    complaint_ref    VARCHAR(24)   NOT NULL UNIQUE,
    area             VARCHAR(10)   NOT NULL,
    category         VARCHAR(40)   NOT NULL,
    priority         VARCHAR(6)    NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    channel          VARCHAR(20)   NOT NULL,
    customer_phone   VARCHAR(20)   NULL,
    customer_name    VARCHAR(120)  NULL,
    reference        VARCHAR(100)  NULL,
    description      VARCHAR(2000) NOT NULL,
    assigned_to      VARCHAR(160)  NULL,
    resolution       VARCHAR(2000) NULL,
    due_at           DATETIME(6)   NOT NULL,
    resolved_at      DATETIME(6)   NULL,
    created_by       VARCHAR(160)  NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    CONSTRAINT chk_complaint_area CHECK (area IN ('MERCHANT','AGENT','GENERAL')),
    CONSTRAINT chk_complaint_priority CHECK (priority IN ('HIGH','NORMAL','LOW')),
    CONSTRAINT chk_complaint_status CHECK (status IN ('OPEN','INVESTIGATING','AWAITING_REVERSAL','RESOLVED','CLOSED','REJECTED')),
    INDEX idx_complaints_status (status, due_at),
    INDEX idx_complaints_phone (customer_phone),
    INDEX idx_complaints_reference (reference)
);

CREATE TABLE complaint_notes (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    complaint_id  BIGINT        NOT NULL,
    note          VARCHAR(2000) NOT NULL,
    created_by    VARCHAR(160)  NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    CONSTRAINT fk_complaint_notes FOREIGN KEY (complaint_id) REFERENCES complaints (id),
    INDEX idx_complaint_notes (complaint_id, id)
);

CREATE TABLE reversal_requests (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    reversal_ref     VARCHAR(24)   NOT NULL UNIQUE,
    kind             VARCHAR(20)   NOT NULL,
    target_ref       VARCHAR(100)  NOT NULL,
    amount_minor     BIGINT        NOT NULL,
    currency         CHAR(3)       NOT NULL,
    summary          VARCHAR(300)  NOT NULL,
    reason           VARCHAR(1000) NOT NULL,
    complaint_id     BIGINT        NULL,
    status           VARCHAR(20)   NOT NULL,
    requested_by     VARCHAR(160)  NOT NULL,
    requested_at     DATETIME(6)   NOT NULL,
    decided_by       VARCHAR(160)  NULL,
    decided_at       DATETIME(6)   NULL,
    decision_note    VARCHAR(1000) NULL,
    executed_at      DATETIME(6)   NULL,
    result_ref       VARCHAR(100)  NULL,
    error            VARCHAR(500)  NULL,
    -- One open request per target, even when two people ask at the same moment.
    open_target      VARCHAR(100)  GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING_APPROVAL','AWAITING_PAYOUT') THEN target_ref END) STORED,
    CONSTRAINT uq_reversal_open_target UNIQUE (open_target),
    CONSTRAINT chk_reversal_kind CHECK (kind IN ('WALLET_REFUND','PAYMENT_REFUND','WALLET_REVERSAL')),
    CONSTRAINT chk_reversal_status CHECK (status IN ('PENDING_APPROVAL','AWAITING_PAYOUT','EXECUTED','REJECTED','FAILED')),
    -- Nobody approves their own request.
    CONSTRAINT chk_reversal_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT fk_reversal_complaint FOREIGN KEY (complaint_id) REFERENCES complaints (id),
    INDEX idx_reversal_status (status, requested_at),
    INDEX idx_reversal_target (target_ref)
);

-- A reversed wallet operation is recorded as REVERSAL lines mirroring the original.
ALTER TABLE wallet_operations DROP CHECK chk_wop_kind;
ALTER TABLE wallet_operations ADD CONSTRAINT chk_wop_kind
    CHECK (kind IN ('TOPUP','PAYMENT','P2P_OUT','P2P_IN','CASH_IN','CASH_OUT','FX_OUT','FX_IN','REFUND','REVERSAL'));
