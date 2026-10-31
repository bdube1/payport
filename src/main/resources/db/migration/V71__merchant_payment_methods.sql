-- Owner, 31 Oct 2026: each merchant chooses the payment methods it accepts; the customer can pick one in the store.
-- No row = accepted (a merchant starts with every method PayPort has switched on).
CREATE TABLE merchant_payment_methods (
    merchant_id BIGINT       NOT NULL,
    method_code VARCHAR(30)  NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    updated_by  VARCHAR(100) NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (merchant_id, method_code)
);

-- The method the customer chose in the store, preselected on the hosted checkout.
ALTER TABLE checkout_sessions ADD COLUMN preferred_method VARCHAR(30) NULL;

-- Store (Commerce) payments run in sandbox (test mode: simulated by phone number, no money moves) until PayPort
-- switches them to live with the owner's approval.
INSERT INTO platform_settings (setting_key, setting_value) VALUES ('commerce_livemode', 'false');

-- Texts PayPort Commerce asks the PSP to send (new-order alerts to the merchant, order updates to the customer).
-- Only counted (a daily cap per merchant); the phone number is not kept.
CREATE TABLE commerce_messages (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT      NOT NULL,
    kind        VARCHAR(20) NOT NULL,
    reference   VARCHAR(60) NULL,
    created_at  DATETIME(6) NOT NULL,
    INDEX idx_commerce_messages (merchant_id, created_at)
);
