-- Owner requests 2026-10-24:
--  * A merchant (e.g. one per branch) or an agent may hold several six-digit codes; the first is the main code.
--    Further codes are applied for in the portal and approved in the admin portal.
--  * Every code has a static QR (a random token linked to the code) that can be printed; generating a new QR
--    replaces the old one and stays linked to the same code.
--  * Payments and agent operations record the code they came through, for reports per code.
ALTER TABLE party_codes
    DROP INDEX uq_party_code_owner,
    ADD COLUMN label VARCHAR(80) NULL,
    ADD COLUMN address VARCHAR(300) NULL,
    ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN qr_token VARCHAR(32) NULL,
    ADD COLUMN qr_issued_at DATETIME(6) NULL,
    ADD CONSTRAINT uq_party_code_qr UNIQUE (qr_token),
    ADD CONSTRAINT chk_party_code_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    ADD INDEX idx_party_code_owner (party_type, party_id);

UPDATE party_codes SET is_primary = TRUE;

CREATE TABLE party_code_requests (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    party_type   VARCHAR(10)   NOT NULL,
    party_id     BIGINT        NOT NULL,
    label        VARCHAR(80)   NOT NULL,
    address      VARCHAR(300)  NULL,
    status       VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
    code         CHAR(6)       NULL,
    note         VARCHAR(300)  NULL,
    requested_by VARCHAR(160)  NOT NULL,
    requested_at DATETIME(6)   NOT NULL,
    decided_by   VARCHAR(160)  NULL,
    decided_at   DATETIME(6)   NULL,
    CONSTRAINT chk_pcr_type CHECK (party_type IN ('AGENT', 'MERCHANT')),
    CONSTRAINT chk_pcr_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    INDEX idx_pcr_status (status, requested_at)
);

ALTER TABLE checkout_sessions ADD COLUMN party_code CHAR(6) NULL;
ALTER TABLE agent_transactions ADD COLUMN party_code CHAR(6) NULL;
