-- Assistant chat: conversations + messages for the Payport Assistant
-- chat widget. Kept deliberately simple (two tables, no soft-delete,
-- no attachments) since this is a lightweight FAQ assistant, not a
-- full support-ticketing system -- TransactionQuery already covers
-- that for merchants.

CREATE TABLE assistant_conversations (
    id                  VARCHAR(64) PRIMARY KEY,   -- client-generated session id (UUID)
    started_at          DATETIME NOT NULL,
    last_activity_at    DATETIME NOT NULL,
    message_count       INT NOT NULL DEFAULT 0,
    source              VARCHAR(30) NOT NULL DEFAULT 'WEBSITE',  -- WEBSITE, CONSUMER_APP, etc (future-proofing)
    INDEX idx_assistant_conv_last_activity (last_activity_at)
);

CREATE TABLE assistant_messages (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id     VARCHAR(64) NOT NULL,
    role                VARCHAR(10) NOT NULL,       -- USER or ASSISTANT
    content             TEXT NOT NULL,
    was_fallback        BOOLEAN NOT NULL DEFAULT FALSE,  -- true if this was a
                                                          -- graceful degrade reply
                                                          -- (rate-limited/error),
                                                          -- not a real model answer
    created_at          DATETIME NOT NULL,
    CONSTRAINT fk_assistant_msg_conversation FOREIGN KEY (conversation_id)
        REFERENCES assistant_conversations(id),
    INDEX idx_assistant_msg_conversation (conversation_id, created_at)
);
