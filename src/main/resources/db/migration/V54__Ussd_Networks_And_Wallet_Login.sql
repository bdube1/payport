-- USSD over the networks' own gateways (Econet, NetOne, Telecel) and wallet login on USSD.
--   ussd_state:    the input path of a session for gateways that send only the latest input, and the
--                  wallet holder once the PIN was checked (the PIN itself is never stored).
--   ussd_networks: each network gateway, switched on and off in ops, with its access key (SHA-256)
--                  and/or allowed IP addresses.

CREATE TABLE ussd_state (
    session_id      VARCHAR(140)  NOT NULL PRIMARY KEY,
    channel         VARCHAR(20)   NOT NULL,
    msisdn          VARCHAR(20)   NOT NULL,
    path            VARCHAR(500)  NOT NULL DEFAULT '',
    wallet_user_id  BIGINT        NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    INDEX idx_ussd_state_updated (updated_at)
);

CREATE TABLE ussd_networks (
    code             VARCHAR(20)   NOT NULL PRIMARY KEY,
    name             VARCHAR(60)   NOT NULL,
    enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    short_code       VARCHAR(20)   NULL,
    allowed_ips      VARCHAR(500)  NULL,
    access_key_hash  CHAR(64)      NULL,
    access_key_hint  VARCHAR(8)    NULL,
    updated_by       VARCHAR(100)  NULL,
    updated_at       DATETIME(6)   NULL
);

INSERT INTO ussd_networks (code, name) VALUES ('ECONET', 'Econet'), ('NETONE', 'NetOne'), ('TELECEL', 'Telecel');

ALTER TABLE ussd_sessions MODIFY session_id VARCHAR(140) NOT NULL;
