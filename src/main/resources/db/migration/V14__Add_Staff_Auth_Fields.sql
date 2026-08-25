-- Staff (OPS/ADMIN/FINANCE) authentication model change: email + password
-- instead of phone + PIN. Customer accounts are completely unaffected --
-- every column here is nullable and customer login logic never reads them.

ALTER TABLE users ADD COLUMN email VARCHAR(255) NULL;
ALTER TABLE users ADD COLUMN password_set_at DATETIME NULL;
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN account_locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN reset_token VARCHAR(255) NULL;
ALTER TABLE users ADD COLUMN reset_token_expires_at DATETIME NULL;

CREATE UNIQUE INDEX idx_users_email ON users(email);

-- Every existing OPS/ADMIN/FINANCE account currently only has a 4-digit PIN
-- stored in pin_hash. That hash still works as a password on their very next
-- login (same BCrypt comparison, no functional change there) -- but it
-- doesn't meet the new password policy, and there's no email on file yet
-- for password reset. Flagging these accounts to force a real password (and
-- email) on their next login, rather than silently leaving them on a
-- 4-digit PIN indefinitely or locking them out immediately.
UPDATE users SET must_change_password = TRUE WHERE role IN ('OPS', 'ADMIN', 'FINANCE');
