-- =====================================================================
-- Payment intents (Stripe-style): one transaction = one intent with attempts.
--
-- transactions.payment_outcome     what the customer was told when the payment window
--                                  closed without a provider answer: TIMED_OUT (provider
--                                  reachable, customer did not complete in time) or
--                                  NO_RESPONSE (the provider never answered). NULL while
--                                  the status alone says it all.
-- transactions.payment_attempt_reference  the reference sent to the provider for the
--                                  current attempt: the transaction reference for attempt 1,
--                                  reference + "-N" for attempt N (providers reject reuse).
-- payment_attempts.attempt_reference, provider_message: per-attempt reference and the
--                                  provider's own wording of the outcome.
-- idempotency_keys                 Idempotency-Key header: a repeated POST returns the
--                                  original response instead of acting twice.
-- =====================================================================
ALTER TABLE transactions
    ADD COLUMN payment_outcome VARCHAR(20) NULL,
    ADD COLUMN payment_attempt_reference VARCHAR(40) NULL,
    ADD COLUMN payment_attempt_count INT NULL DEFAULT 0;

ALTER TABLE payment_attempts
    ADD COLUMN attempt_reference VARCHAR(40) NULL,
    ADD COLUMN provider_message VARCHAR(1000) NULL;

CREATE INDEX idx_pattempt_attempt_ref ON payment_attempts (attempt_reference);

CREATE TABLE idempotency_keys (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_hash       CHAR(64)      NOT NULL,
    idem_key         VARCHAR(255)  NOT NULL,
    method           VARCHAR(10)   NOT NULL,
    path             VARCHAR(500)  NOT NULL,
    request_sha256   CHAR(64)      NOT NULL,
    status           VARCHAR(12)   NOT NULL,
    response_status  INT           NULL,
    response_type    VARCHAR(200)  NULL,
    response_body    MEDIUMTEXT    NULL,
    created_at       DATETIME(6)   NOT NULL,
    completed_at     DATETIME(6)   NULL,
    expires_at       DATETIME(6)   NOT NULL,
    CONSTRAINT uq_idem UNIQUE (scope_hash, idem_key),
    CONSTRAINT chk_idem_status CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    INDEX idx_idem_expiry (expires_at)
);
