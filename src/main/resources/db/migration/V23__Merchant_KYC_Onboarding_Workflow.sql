-- Merchants Module: KYC documents, review notes, expanded status flow.

ALTER TABLE merchants DROP CHECK chk_merchant_status;
ALTER TABLE merchants ADD CONSTRAINT chk_merchant_status
    CHECK (status IN ('PENDING','UNDER_REVIEW','UPDATE_REQUIRED','ACTIVE','SUSPENDED','CLOSED','REJECTED'));

CREATE TABLE merchant_documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    document_type VARCHAR(50) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255),
    uploaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_merchant_documents_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

CREATE INDEX idx_merchant_documents_merchant ON merchant_documents(merchant_id);

CREATE TABLE merchant_review_notes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    note TEXT NOT NULL,
    requested_by_staff_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME,

    CONSTRAINT fk_merchant_review_notes_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT chk_review_note_status CHECK (status IN ('OPEN','RESOLVED'))
);

CREATE INDEX idx_merchant_review_notes_merchant ON merchant_review_notes(merchant_id);
