-- PayPort Commerce Phase 1 (owner, 2026-10-31) and channel changes.

-- 1. One event stream for everything that happens to money (payments, checkouts, refunds). Written by the PSP as the
--    change happens; read in order by PayPort Commerce (and later reporting and PayPort Intelligence) through
--    /api/v1/commerce/events. Append-only: an event is never changed. dedupe_key makes a state change produce
--    exactly one event even when two threads record it at once. Payloads carry no card data, names or phone numbers.
CREATE TABLE platform_events (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id     CHAR(36)     NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    version      INT          NOT NULL DEFAULT 1,
    source       VARCHAR(20)  NOT NULL,
    merchant_id  BIGINT       NULL,
    subject_ref  VARCHAR(100) NULL,
    dedupe_key   VARCHAR(190) NOT NULL,
    livemode     BOOLEAN      NOT NULL DEFAULT FALSE,
    payload      JSON         NOT NULL,
    occurred_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uq_platform_events_id UNIQUE (event_id),
    CONSTRAINT uq_platform_events_dedupe UNIQUE (dedupe_key),
    INDEX idx_platform_events_type (event_type, id),
    INDEX idx_platform_events_merchant (merchant_id, id),
    INDEX idx_platform_events_time (occurred_at)
);

-- 2. A merchant refunds its own customer from its own balance (owner, 2026-10-31). The refund is listed in the
--    central reversal register as MERCHANT_REFUND, waiting for the payout to the customer, so every refund is in one place.
ALTER TABLE reversal_requests DROP CHECK chk_reversal_kind;
ALTER TABLE reversal_requests ADD CONSTRAINT chk_reversal_kind CHECK (kind IN ('WALLET_REFUND','PAYMENT_REFUND','WALLET_REVERSAL','MERCHANT_REFUND'));
ALTER TABLE refunds ADD COLUMN reversal_ref VARCHAR(24) NULL;

-- 3. WhatsApp PINs are typed in the chat again (a WhatsApp bundle may have no internet for a web page): the one-time
--    PIN page and its table are gone.
DROP TABLE IF EXISTS channel_pin_requests;
