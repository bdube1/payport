-- =====================================================================
-- Phase 3: reconciliation and settlement.
--
-- Reconciliation never changes a payment or a balance: it records what differs, with
-- evidence, for a person to resolve ("nothing auto-adjusts").
--
-- Settlement runs alongside the legacy settlement (settlements table) until the parallel
-- run is signed off: in PARALLEL mode batches can be drafted and approved but not
-- instructed (payport.settlement.mode).
-- =====================================================================

CREATE TABLE recon_statements (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_code  VARCHAR(30)   NOT NULL,
    file_name      VARCHAR(255)  NOT NULL,
    file_sha256    CHAR(64)      NOT NULL,
    line_count     INT           NOT NULL,
    period_start   DATETIME(6)   NULL,
    period_end     DATETIME(6)   NULL,
    imported_by    VARCHAR(120)  NOT NULL,
    imported_at    DATETIME(6)   NOT NULL,
    CONSTRAINT uq_rstmt_file UNIQUE (provider_code, file_sha256)
);

CREATE TABLE recon_statement_lines (
    id                   BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    statement_id         BIGINT        NOT NULL,
    line_no              INT           NOT NULL,
    references_text      VARCHAR(1000) NULL,
    amount_minor         BIGINT        NOT NULL,
    currency             VARCHAR(3)    NULL,
    occurred_at          DATETIME(6)   NULL,
    raw_row              TEXT          NULL,
    match_status         VARCHAR(12)   NOT NULL,
    matched_transaction  VARCHAR(40)   NULL,
    CONSTRAINT fk_rline_stmt FOREIGN KEY (statement_id) REFERENCES recon_statements (id),
    CONSTRAINT uq_rline UNIQUE (statement_id, line_no),
    CONSTRAINT chk_rline_status CHECK (match_status IN ('UNMATCHED','MATCHED','EXCEPTION','SKIPPED')),
    INDEX idx_rline_tx (matched_transaction, match_status)
);

CREATE TABLE recon_runs (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider_code   VARCHAR(30)   NOT NULL,
    statement_id    BIGINT        NOT NULL,
    window_start    DATETIME(6)   NOT NULL,
    window_end      DATETIME(6)   NOT NULL,
    matched         INT           NOT NULL,
    exceptions      INT           NOT NULL,
    summary         VARCHAR(2000) NULL,
    run_by          VARCHAR(120)  NOT NULL,
    run_at          DATETIME(6)   NOT NULL,
    CONSTRAINT fk_rrun_stmt FOREIGN KEY (statement_id) REFERENCES recon_statements (id)
);

CREATE TABLE recon_exceptions (
    id                     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dedupe_key             VARCHAR(200)  NOT NULL,
    category               VARCHAR(40)   NOT NULL,
    source                 VARCHAR(12)   NOT NULL,
    provider_code          VARCHAR(30)   NULL,
    transaction_reference  VARCHAR(40)   NULL,
    provider_reference     VARCHAR(200)  NULL,
    statement_line_id      BIGINT        NULL,
    expected_minor         BIGINT        NULL,
    actual_minor           BIGINT        NULL,
    currency               VARCHAR(3)    NULL,
    detail                 VARCHAR(1000) NULL,
    status                 VARCHAR(10)   NOT NULL,
    resolution             VARCHAR(30)   NULL,
    resolution_note        VARCHAR(1000) NULL,
    resolved_by            VARCHAR(120)  NULL,
    resolved_at            DATETIME(6)   NULL,
    created_at             DATETIME(6)   NOT NULL,
    last_seen_at           DATETIME(6)   NOT NULL,
    CONSTRAINT uq_rexc_dedupe UNIQUE (dedupe_key),
    CONSTRAINT chk_rexc_status CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT chk_rexc_resolved CHECK (status = 'OPEN' OR (resolution IS NOT NULL AND resolved_by IS NOT NULL)),
    INDEX idx_rexc_open (status, category, created_at),
    INDEX idx_rexc_tx (transaction_reference)
);

CREATE TABLE stl_batches (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_ref           VARCHAR(40)   NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    mode                VARCHAR(10)   NOT NULL,
    cutoff_at           DATETIME(6)   NOT NULL,
    status              VARCHAR(12)   NOT NULL,
    item_count          INT           NOT NULL,
    total_net_minor     BIGINT        NOT NULL,
    approvals_required  INT           NOT NULL,
    created_by          VARCHAR(120)  NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    approved_at         DATETIME(6)   NULL,
    instructed_at       DATETIME(6)   NULL,
    completed_at        DATETIME(6)   NULL,
    file_format         VARCHAR(30)   NULL,
    file_name           VARCHAR(200)  NULL,
    file_sha256         CHAR(64)      NULL,
    file_content        MEDIUMTEXT    NULL,
    CONSTRAINT uq_stlb_ref UNIQUE (batch_ref),
    CONSTRAINT chk_stlb_status CHECK (status IN ('DRAFT','APPROVED','INSTRUCTED','COMPLETED','CANCELLED')),
    CONSTRAINT chk_stlb_mode CHECK (mode IN ('PARALLEL','LIVE')),
    CONSTRAINT chk_stlb_total CHECK (total_net_minor >= 0),
    CONSTRAINT chk_stlb_instructed CHECK (status NOT IN ('INSTRUCTED','COMPLETED') OR (file_sha256 IS NOT NULL AND mode = 'LIVE'))
);

CREATE TABLE stl_batch_approvals (
    batch_id     BIGINT        NOT NULL,
    approver     VARCHAR(120)  NOT NULL,
    approved_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (batch_id, approver),
    CONSTRAINT fk_stla_batch FOREIGN KEY (batch_id) REFERENCES stl_batches (id)
);

CREATE TABLE stl_items (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    batch_id          BIGINT        NOT NULL,
    merchant_id       BIGINT        NOT NULL,
    currency          VARCHAR(3)    NOT NULL,
    session_count     INT           NOT NULL,
    gross_minor       BIGINT        NOT NULL,
    fee_minor         BIGINT        NOT NULL,
    refund_minor      BIGINT        NOT NULL,
    net_minor         BIGINT        NOT NULL,
    destination       VARCHAR(1000) NULL,
    instruction_ref   VARCHAR(60)   NOT NULL,
    status            VARCHAR(10)   NOT NULL,
    bank_reference    VARCHAR(120)  NULL,
    proof_ref         VARCHAR(200)  NULL,
    proof_sha256      CHAR(64)      NULL,
    paid_at           DATETIME(6)   NULL,
    return_reason     VARCHAR(500)  NULL,
    updated_at        DATETIME(6)   NOT NULL,
    CONSTRAINT fk_stli_batch FOREIGN KEY (batch_id) REFERENCES stl_batches (id),
    CONSTRAINT uq_stli_merchant UNIQUE (batch_id, merchant_id),
    CONSTRAINT uq_stli_instruction UNIQUE (instruction_ref),
    CONSTRAINT chk_stli_status CHECK (status IN ('PENDING','INSTRUCTED','PAID','RETURNED','FAILED')),
    -- PAID only with the bank's reference and proof.
    CONSTRAINT chk_stli_paid CHECK (status <> 'PAID' OR (bank_reference IS NOT NULL AND proof_sha256 IS NOT NULL AND paid_at IS NOT NULL)),
    CONSTRAINT chk_stli_amounts CHECK (net_minor >= 0 AND gross_minor >= 0 AND fee_minor >= 0 AND refund_minor >= 0)
);

-- Which checkout sessions each item pays out. A session can be held by one live claim only.
CREATE TABLE stl_item_sessions (
    item_id     BIGINT  NOT NULL,
    session_id  BIGINT  NOT NULL,
    net_minor   BIGINT  NOT NULL,
    PRIMARY KEY (item_id, session_id),
    CONSTRAINT fk_stlis_item FOREIGN KEY (item_id) REFERENCES stl_items (id)
);

CREATE TABLE stl_session_claims (
    session_id  BIGINT  NOT NULL PRIMARY KEY,
    item_id     BIGINT  NOT NULL,
    CONSTRAINT fk_stlc_item FOREIGN KEY (item_id) REFERENCES stl_items (id)
);
