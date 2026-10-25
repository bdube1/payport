-- USSD and WhatsApp (owner, 2026-10-25): a PIN typed on one step of a session (registration, change of PIN) is
-- checked or hashed on that step and only its hash or a "checked" flag is kept here, never the PIN itself; the stored
-- session path carries "#" in its place. Rows go with the session (cleared after 6 hours).
CREATE TABLE channel_session_secrets (
    session_id  VARCHAR(140) NOT NULL,
    name        VARCHAR(40)  NOT NULL,
    value       VARCHAR(255) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (session_id, name),
    INDEX idx_channel_session_secrets_created (created_at)
);
