-- Redesigns transaction queries from a single message + single response
-- into a proper chronological thread -- both the merchant and ops can add
-- messages at any time, not just one round each. Reopening is its own
-- event type in the same thread, so its timestamp shows up naturally in
-- chronological order rather than needing a separate field.

ALTER TABLE transaction_queries ADD COLUMN reference_number VARCHAR(30) NULL AFTER id;
CREATE UNIQUE INDEX idx_transaction_queries_reference ON transaction_queries(reference_number);

CREATE TABLE query_messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_id BIGINT NOT NULL,
    sender_type VARCHAR(20) NOT NULL,
    sender_id BIGINT NOT NULL,
    sender_name VARCHAR(150),
    event_type VARCHAR(20) NOT NULL DEFAULT 'MESSAGE',
    message TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_query_messages_query FOREIGN KEY (query_id) REFERENCES transaction_queries(id)
);

CREATE INDEX idx_query_messages_query ON query_messages(query_id, created_at);
