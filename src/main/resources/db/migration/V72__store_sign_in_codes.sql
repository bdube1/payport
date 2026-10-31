-- Owner, 31 Oct 2026: shoppers sign in to the PayPort Store with their PayPort login. The app hands the store a one-time
-- code (two minutes, used once); the store exchanges it, server to server, for the customer's id, name and phone.
CREATE TABLE store_login_codes (
    code_hash  CHAR(64)    PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL
);
