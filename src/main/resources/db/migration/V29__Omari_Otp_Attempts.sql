-- Omari-confirmed policy: customer gets up to 3 OTP attempts before the
-- payment is finally failed. NOT NULL DEFAULT 0 so existing rows and any
-- code path that doesn't explicitly set this never hits a null check.
ALTER TABLE transactions ADD COLUMN omari_otp_attempts INT NOT NULL DEFAULT 0;
