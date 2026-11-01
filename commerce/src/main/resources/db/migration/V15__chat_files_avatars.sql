-- Chat like WhatsApp (owner, 2 Nov 2026): photos, videos, voice notes and audio, documents and contacts in a
-- conversation, and a profile picture for customers and sellers. Files are checked by their content, stored encrypted,
-- and served only through short-lived signed links.
CREATE TABLE chat_files (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    chat_id       BIGINT       NULL,
    kind          VARCHAR(10)  NOT NULL,
    content_type  VARCHAR(80)  NOT NULL,
    name_enc      VARCHAR(600) NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    data_enc      LONGTEXT     NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT chk_chat_file_kind CHECK (kind IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'AVATAR')),
    INDEX idx_chat_files_chat (chat_id)
);

ALTER TABLE chat_messages ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'TEXT';
ALTER TABLE chat_messages ADD COLUMN file_id BIGINT NULL;

CREATE TABLE chat_avatars (
    owner_type  VARCHAR(10) NOT NULL,
    owner_id    BIGINT      NOT NULL,
    file_id     BIGINT      NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_type, owner_id),
    CONSTRAINT chk_avatar_owner CHECK (owner_type IN ('SHOPPER', 'MERCHANT'))
);
