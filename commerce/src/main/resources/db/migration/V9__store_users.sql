-- Owner, 31 Oct 2026: Payport Store users are the store's own accounts (email or phone, and a password), separate
-- from Payport app customers. Loyalty points belong to the store user's account, or, for orders placed on USSD,
-- WhatsApp or the app, to the phone number the order came from.
ALTER TABLE shoppers MODIFY psp_user_id BIGINT NULL;
ALTER TABLE shoppers ADD COLUMN email         VARCHAR(190) NULL;
ALTER TABLE shoppers ADD COLUMN password_hash VARCHAR(200) NULL;
ALTER TABLE shoppers ADD COLUMN failed_logins INT          NOT NULL DEFAULT 0;
ALTER TABLE shoppers ADD COLUMN locked_until  DATETIME(6)  NULL;
CREATE UNIQUE INDEX uq_shopper_email ON shoppers (email);
CREATE UNIQUE INDEX uq_shopper_phone ON shoppers (phone);

CREATE TABLE shopper_resets (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    shopper_id BIGINT      NOT NULL,
    code_hash  CHAR(64)    NOT NULL,
    attempts   INT         NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_shopper_resets (shopper_id)
);

ALTER TABLE loyalty_points MODIFY phone9 VARCHAR(24) NOT NULL;

-- Payport's own pick for "Featured stores" on the front page (set by Payport, not by merchants).
ALTER TABLE stores ADD COLUMN directory_featured BOOLEAN NOT NULL DEFAULT FALSE;
-- And the order stores are shown in (lowest first; stores without one follow, by name).
ALTER TABLE stores ADD COLUMN directory_rank INT NULL;
