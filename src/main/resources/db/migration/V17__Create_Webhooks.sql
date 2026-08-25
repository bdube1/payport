-- Merchants Module, Phase 2: Webhooks.

CREATE TABLE webhooks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    url VARCHAR(1000) NOT NULL,
    secret VARCHAR(100) NOT NULL,          -- raw, not hashed -- the merchant needs this to verify signatures themselves
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DISABLED
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_webhooks_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id)
);

CREATE INDEX idx_webhooks_merchant_id ON webhooks(merchant_id);

CREATE TABLE webhook_deliveries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    webhook_id BIGINT NOT NULL,
    checkout_session_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,        -- checkout_session.paid | checkout_session.failed
    payload TEXT NOT NULL,                  -- the exact JSON body sent
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NULL,
    last_attempt_at DATETIME NULL,
    response_status INT NULL,
    response_body VARCHAR(2000) NULL,
    delivered_at DATETIME NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | DELIVERED | FAILED (exhausted retries)
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_webhook_deliveries_webhook FOREIGN KEY (webhook_id) REFERENCES webhooks(id)
);

CREATE INDEX idx_webhook_deliveries_webhook_id ON webhook_deliveries(webhook_id);
CREATE INDEX idx_webhook_deliveries_status_next_attempt ON webhook_deliveries(status, next_attempt_at);
