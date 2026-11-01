-- Payport Tap (owner, 2 Nov 2026): pay from the Payport wallet by tapping the phone on a merchant's phone or POS (NFC).
-- The customer confirms with their PIN first; the app then offers a one-time token over NFC for 90 seconds. The
-- merchant's device reads it and charges an amount up to the customer's limit. Only a hash of the token is kept.
CREATE TABLE tap_tokens (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash    CHAR(64)     NOT NULL,
    user_id       BIGINT       NOT NULL,
    currency      CHAR(3)      NOT NULL,
    max_minor     BIGINT       NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    used_at       DATETIME(6)  NULL,
    merchant_id   BIGINT       NULL,
    amount_minor  BIGINT       NULL,
    reference     VARCHAR(60)  NULL,
    result        VARCHAR(20)  NULL,
    message       VARCHAR(300) NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_tap_token UNIQUE (token_hash),
    INDEX idx_tap_user (user_id, created_at)
);
