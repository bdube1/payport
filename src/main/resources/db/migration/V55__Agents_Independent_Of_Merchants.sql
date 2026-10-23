-- Agents are their own kind of business, separate from merchants (owner instruction 2026-10-23):
-- a merchant receives payments and settlement; an agent provides cash-in and cash-out, holds cash and
-- e-float and earns commission. A merchant is never an agent by default. Agents get their own
-- portal logins (agent_users).

ALTER TABLE agents DROP INDEX uq_agent_merchant;
ALTER TABLE agents DROP COLUMN merchant_id;
ALTER TABLE agents
    ADD COLUMN contact_person VARCHAR(140) NULL AFTER name,
    ADD COLUMN email          VARCHAR(160) NULL AFTER phone,
    ADD COLUMN id_number      VARCHAR(40)  NULL AFTER email,
    ADD COLUMN application_reference VARCHAR(30) NULL AFTER agent_code,
    ADD CONSTRAINT uq_agent_phone UNIQUE (phone),
    ADD CONSTRAINT uq_agent_application_ref UNIQUE (application_reference);

CREATE TABLE agent_users (
    id                     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    agent_id               BIGINT        NOT NULL,
    full_name              VARCHAR(140)  NOT NULL,
    email                  VARCHAR(160)  NOT NULL,
    password_hash          VARCHAR(100)  NOT NULL,
    status                 VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts  INT           NOT NULL DEFAULT 0,
    account_locked         BOOLEAN       NOT NULL DEFAULT FALSE,
    must_change_password   BOOLEAN       NOT NULL DEFAULT TRUE,
    reset_token_hash       CHAR(64)      NULL,
    reset_token_expires_at DATETIME(6)   NULL,
    last_login_at          DATETIME(6)   NULL,
    created_by             VARCHAR(160)  NOT NULL,
    created_at             DATETIME(6)   NOT NULL,
    CONSTRAINT uq_agent_user_email UNIQUE (email),
    CONSTRAINT fk_agent_user_agent FOREIGN KEY (agent_id) REFERENCES agents (id),
    CONSTRAINT chk_agent_user_status CHECK (status IN ('ACTIVE','DISABLED'))
);

