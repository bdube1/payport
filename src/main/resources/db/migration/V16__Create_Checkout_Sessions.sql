-- Merchants Module, Phase 1: Checkout Sessions.
--
-- A merchant checkout is fundamentally different from every existing
-- product type (airtime, data, ZESA, ...): the payment collection IS the
-- entire transaction, there's nothing to fulfil afterward. Rather than
-- build a second, parallel payment-collection pipeline, this reuses the
-- existing hardened one via a genuine (but generic) ProductType +
-- FulfilmentProviderAdaptor pair -- MerchantCheckoutFulfilmentAdaptor is a
-- deliberate no-op, since for a merchant charge, successful payment IS
-- successful fulfilment.

INSERT INTO service_providers (code, name, provider_type, is_active)
VALUES ('MERCHANT', 'Merchant Checkout', 'MERCHANT', TRUE);

INSERT INTO product_types (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents, currency)
VALUES ('MERCHANT_CHECKOUT', 'Merchant Checkout', 'MERCHANT', 'MERCHANT', TRUE, 1, 100000000, 'USD');

INSERT INTO product_types (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents, currency)
VALUES ('MERCHANT_CHECKOUT_ZWG', 'Merchant Checkout (ZWG)', 'MERCHANT', 'MERCHANT', TRUE, 1, 100000000, 'ZWG');

CREATE TABLE checkout_sessions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    reference VARCHAR(100) NOT NULL,          -- merchant-provided, unique per merchant
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    description VARCHAR(500),

    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',  -- CREATED | PENDING | PAID | FAILED | EXPIRED | CANCELLED

    customer_phone VARCHAR(30),
    customer_email VARCHAR(255),

    success_url VARCHAR(1000),
    cancel_url VARCHAR(1000),
    metadata TEXT,                            -- merchant-defined JSON passthrough

    transaction_reference VARCHAR(100),        -- FK-by-value to transactions.reference once payment is attempted
    api_key_id BIGINT,                         -- which key created this session, for audit

    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_checkout_sessions_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT chk_checkout_session_status CHECK (status IN ('CREATED','PENDING','PAID','FAILED','EXPIRED','CANCELLED'))
);

CREATE UNIQUE INDEX idx_checkout_sessions_merchant_reference ON checkout_sessions(merchant_id, reference);
CREATE INDEX idx_checkout_sessions_status ON checkout_sessions(status);
CREATE INDEX idx_checkout_sessions_transaction_reference ON checkout_sessions(transaction_reference);
