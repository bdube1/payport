-- Merchants Module, Phase 4: Settlements.
--
-- A settlement "claims" a set of checkout_sessions by stamping them with
-- its own id -- that's what makes "what's still pending" trivially
-- queryable (settlement_id IS NULL) and guarantees a session can never
-- be double-counted across two settlement runs.

ALTER TABLE checkout_sessions ADD COLUMN settlement_id BIGINT NULL AFTER net_amount_cents;
CREATE INDEX idx_checkout_sessions_settlement ON checkout_sessions(settlement_id);

CREATE TABLE settlements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    period_start DATETIME NOT NULL,
    period_end DATETIME NOT NULL,
    currency VARCHAR(3) NOT NULL,

    session_count INT NOT NULL DEFAULT 0,
    gross_amount_cents BIGINT NOT NULL DEFAULT 0,
    commission_amount_cents BIGINT NOT NULL DEFAULT 0,
    refund_amount_cents BIGINT NOT NULL DEFAULT 0,
    net_amount_cents BIGINT NOT NULL DEFAULT 0,

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | PROCESSING | PAID
    payout_reference VARCHAR(100),
    paid_at DATETIME,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_settlements_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT chk_settlement_status CHECK (status IN ('PENDING','PROCESSING','PAID'))
);

ALTER TABLE checkout_sessions ADD CONSTRAINT fk_checkout_sessions_settlement
    FOREIGN KEY (settlement_id) REFERENCES settlements(id);

CREATE INDEX idx_settlements_merchant ON settlements(merchant_id);
CREATE INDEX idx_settlements_status ON settlements(status);
