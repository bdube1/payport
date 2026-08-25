-- Merchant transaction queries: file attachments. Same validated,
-- local-disk storage pattern as merchant_documents (KYC) -- see
-- MerchantDocumentService for the exact rules this mirrors.

CREATE TABLE query_attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    file_size_bytes BIGINT,
    uploaded_by_type VARCHAR(20) NOT NULL,
    uploaded_by_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_query_attachments_query FOREIGN KEY (query_id) REFERENCES transaction_queries(id)
);

CREATE INDEX idx_query_attachments_query ON query_attachments(query_id);
