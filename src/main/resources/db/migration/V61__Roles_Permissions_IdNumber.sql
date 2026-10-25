-- Owner requests 2026-10-25:
--  * Wallet registration records the customer's ID number; it shows on the customer record.
--  * Roles are configurable: every permission in the system is listed; roles (built-in or new) get the
--    permissions chosen for them; staff see only the menus their role's permissions allow.
ALTER TABLE users
    ADD COLUMN id_number VARCHAR(40) NULL,
    ADD COLUMN custom_role VARCHAR(40) NULL;

CREATE TABLE staff_roles (
    code         VARCHAR(40)   NOT NULL PRIMARY KEY,
    name         VARCHAR(80)   NOT NULL,
    description  VARCHAR(300)  NULL,
    base_role    VARCHAR(20)   NOT NULL,
    built_in     BOOLEAN       NOT NULL DEFAULT FALSE,
    updated_by   VARCHAR(160)  NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    CONSTRAINT chk_staff_role_base CHECK (base_role IN ('ADMIN', 'OPS', 'FINANCE', 'COMPLIANCE', 'OPS_MANAGER'))
);

CREATE TABLE staff_role_permissions (
    role_code    VARCHAR(40)   NOT NULL,
    permission   VARCHAR(60)   NOT NULL,
    PRIMARY KEY (role_code, permission),
    CONSTRAINT fk_srp_role FOREIGN KEY (role_code) REFERENCES staff_roles (code) ON DELETE CASCADE
);

-- PayPort's USSD short code is *699# on every network (owner, 2026-10-25). Networks stay switched off until enabled.
UPDATE ussd_networks SET short_code = '*699#' WHERE short_code IS NULL OR short_code = '';
