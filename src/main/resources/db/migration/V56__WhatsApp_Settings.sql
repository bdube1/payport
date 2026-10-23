-- WhatsApp Cloud API connection, set from the ops console. Secrets are stored encrypted (SecretBox,
-- "enc:v1:" AES-256-GCM); a value left empty falls back to the application properties.
CREATE TABLE whatsapp_settings (
    id               TINYINT       NOT NULL PRIMARY KEY,
    business_number  VARCHAR(20)   NULL,
    phone_number_id  VARCHAR(40)   NULL,
    waba_id          VARCHAR(40)   NULL,
    access_token     VARCHAR(2000) NULL,
    app_secret       VARCHAR(500)  NULL,
    verify_token     VARCHAR(500)  NULL,
    api_base         VARCHAR(200)  NULL,
    updated_by       VARCHAR(160)  NULL,
    updated_at       DATETIME(6)   NULL,
    CONSTRAINT chk_whatsapp_settings_single CHECK (id = 1)
);

INSERT INTO whatsapp_settings (id, business_number, updated_by, updated_at) VALUES (1, '+263719183557', 'owner', NOW(6));
