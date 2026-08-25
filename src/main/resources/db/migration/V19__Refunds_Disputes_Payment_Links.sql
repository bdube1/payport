-- Merchants Module: Refunds, Disputes, Payment Links.
--
-- Being upfront about scope here: no payment provider adaptor in this
-- codebase (EcoCash/OneMoney/Omari/ZimSwitch) has a refund-execution
-- method built. This is a tracking and workflow system -- like Stripe's
-- own refund object -- not an automatic mobile-money reversal. Actually
-- moving money back requires a real integration per provider, which is
-- flagged clearly in the API responses this feeds, not hidden.

ALTER TABLE checkout_sessions DROP CHECK chk_checkout_session_status;
ALTER TABLE checkout_sessions ADD CONSTRAINT chk_checkout_session_status
    CHECK (status IN ('CREATED','PENDING','PAID','FAILED','EXPIRED','CANCELLED','PARTIALLY_REFUNDED','REFUNDED'));

CREATE TABLE refunds (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    checkout_session_id BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | COMPLETED | FAILED
    initiated_by_type VARCHAR(20) NOT NULL,          -- MERCHANT_USER | STAFF
    initiated_by_id BIGINT NOT NULL,
    provider_reference VARCHAR(100),                 -- populated once real provider refund execution exists
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_refunds_checkout_session FOREIGN KEY (checkout_session_id) REFERENCES checkout_sessions(id),
    CONSTRAINT chk_refund_status CHECK (status IN ('PENDING','COMPLETED','FAILED'))
);

CREATE INDEX idx_refunds_checkout_session ON refunds(checkout_session_id);

-- Disputes come from the card network (via ZimSwitch), not something a
-- merchant self-reports -- created by ops, visible read-only to the
-- merchant. Mobile money has no real chargeback mechanism in Zimbabwe
-- today, so this is scoped to card transactions in practice, though
-- nothing here technically prevents opening one against any session.
CREATE TABLE disputes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    checkout_session_id BIGINT NOT NULL,
    amount_cents BIGINT NOT NULL,
    reason VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',      -- OPEN | NEEDS_RESPONSE | WON | LOST
    evidence_notes TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at DATETIME,

    CONSTRAINT fk_disputes_checkout_session FOREIGN KEY (checkout_session_id) REFERENCES checkout_sessions(id),
    CONSTRAINT chk_dispute_status CHECK (status IN ('OPEN','NEEDS_RESPONSE','WON','LOST'))
);

CREATE INDEX idx_disputes_checkout_session ON disputes(checkout_session_id);
CREATE INDEX idx_disputes_status ON disputes(status);

-- Payment Links: a merchant-dashboard-created, reusable or single-use
-- checkout without needing their own API integration -- the same hosted
-- checkout page, just created a different way, for non-technical
-- merchants. A link generates a NEW checkout_session each time it's paid
-- (so existing session/status/webhook machinery is reused untouched);
-- this table is the template it's generated from.
CREATE TABLE payment_links (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    slug VARCHAR(40) NOT NULL,               -- short, URL-safe, part of the public link
    amount_cents BIGINT,                     -- NULL = customer enters their own amount
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DISABLED
    reusable BOOLEAN NOT NULL DEFAULT TRUE,   -- false = disables itself after first successful payment
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_links_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT chk_payment_link_status CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE UNIQUE INDEX idx_payment_links_slug ON payment_links(slug);
