-- Merchants Module: Transaction Queries.
--
-- Deliberately separate from disputes. A dispute is a chargeback --
-- opened by the card network against a merchant, logged by ops. A query
-- is a merchant asking Payport a question about a specific transaction --
-- opened by the merchant, answered by ops. Conflating the two would blur
-- something that needs to stay precise: one carries real financial
-- weight, the other is support correspondence.

CREATE TABLE transaction_queries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    checkout_session_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    raised_by_merchant_user_id BIGINT NOT NULL,

    subject VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,

    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',  -- OPEN | IN_PROGRESS | RESOLVED

    ops_response TEXT,
    responded_by_user_id BIGINT,
    responded_at DATETIME,

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME,

    CONSTRAINT fk_transaction_queries_session FOREIGN KEY (checkout_session_id) REFERENCES checkout_sessions(id),
    CONSTRAINT fk_transaction_queries_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT chk_transaction_query_status CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED'))
);

CREATE INDEX idx_transaction_queries_merchant ON transaction_queries(merchant_id);
CREATE INDEX idx_transaction_queries_session ON transaction_queries(checkout_session_id);
CREATE INDEX idx_transaction_queries_status ON transaction_queries(status);
