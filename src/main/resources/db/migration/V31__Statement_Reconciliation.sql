-- Statement reconciliation: ops staff upload a provider's settlement/
-- statement export (Excel or PDF), the system parses it and matches
-- each line against Payport's own Transaction records, producing a
-- discrepancy report. Two tables: one row per upload (the run, with
-- summary counts), one row per line-item result (matched, provider-only,
-- Payport-only, or amount-mismatch) so every individual discrepancy is
-- traceable and can be annotated/resolved by a person, not just totals.

CREATE TABLE reconciliation_runs (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider                VARCHAR(30) NOT NULL,   -- ECOCASH, ONEMONEY, NETONE, TELECEL, INNBUCKS, OMARI
    uploaded_file_name      VARCHAR(255) NOT NULL,
    uploaded_by             VARCHAR(100) NOT NULL,  -- ops username
    uploaded_at             DATETIME NOT NULL,
    period_start            DATETIME,               -- earliest line timestamp found in the file
    period_end              DATETIME,                -- latest line timestamp found in the file
    total_provider_lines    INT NOT NULL DEFAULT 0,  -- transaction-type lines only, sweeps excluded
    matched_count           INT NOT NULL DEFAULT 0,
    amount_mismatch_count   INT NOT NULL DEFAULT 0,
    provider_only_count     INT NOT NULL DEFAULT 0,  -- provider has it, Payport doesn't
    payport_only_count      INT NOT NULL DEFAULT 0,  -- Payport has it, provider statement doesn't
    status                  VARCHAR(20) NOT NULL DEFAULT 'COMPLETED', -- COMPLETED, FAILED
    error_message           VARCHAR(1000),
    INDEX idx_recon_run_provider (provider, uploaded_at)
);

CREATE TABLE reconciliation_matches (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id                  BIGINT NOT NULL,
    match_type              VARCHAR(20) NOT NULL,   -- MATCHED, AMOUNT_MISMATCH, PROVIDER_ONLY, PAYPORT_ONLY
    match_confidence        VARCHAR(20),             -- EXACT_REFERENCE, FUZZY, NONE
    transaction_reference   VARCHAR(24),             -- Payport's own reference, where matched
    provider_reference      VARCHAR(255),            -- best candidate reference found in the statement line
    provider_amount_cents   BIGINT,
    payport_amount_cents    BIGINT,
    provider_phone          VARCHAR(20),
    provider_timestamp      DATETIME,
    raw_provider_row        TEXT,                    -- the original line, for audit/debugging a disputed match
    notes                   TEXT,                    -- ops annotation
    resolved                BOOLEAN NOT NULL DEFAULT FALSE,
    resolved_by             VARCHAR(100),
    resolved_at             DATETIME,
    CONSTRAINT fk_recon_match_run FOREIGN KEY (run_id) REFERENCES reconciliation_runs(id),
    INDEX idx_recon_match_run (run_id, match_type),
    INDEX idx_recon_match_tx_ref (transaction_reference)
);
