-- WhatsApp (owner, 2026-10-30): a PIN is never typed into the chat. When one is needed the customer gets a one-time
-- link to a PayPort page; only the SHA-256 of the link token is stored. Single use, 10 minutes.
CREATE TABLE channel_pin_requests (
    token_hash   CHAR(64)     NOT NULL PRIMARY KEY,
    session_id   VARCHAR(140) NOT NULL,
    wa_id        VARCHAR(20)  NOT NULL,
    purpose      VARCHAR(12)  NOT NULL,
    user_id      BIGINT       NULL,
    expires_at   DATETIME(6)  NOT NULL,
    used_at      DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    CONSTRAINT chk_channel_pin_purpose CHECK (purpose IN ('LOGIN', 'REGISTER', 'CHANGE')),
    INDEX idx_channel_pin_session (session_id),
    INDEX idx_channel_pin_expires (expires_at)
);

-- Messages PayPort sends on WhatsApp without being asked in that moment (after a PIN page, payment updates):
-- what was sent and whether it went, for support and for the test phone.
CREATE TABLE whatsapp_outbox (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    wa_id       VARCHAR(20)   NOT NULL,
    body        VARCHAR(2000) NOT NULL,
    kind        VARCHAR(30)   NOT NULL,
    sent        BOOLEAN       NOT NULL DEFAULT FALSE,
    send_error  VARCHAR(300)  NULL,
    created_at  DATETIME(6)   NOT NULL,
    INDEX idx_whatsapp_outbox_wa (wa_id, id)
);
