-- =====================================================
-- V5: Enhance Payment Methods (MySQL Compatible)
-- =====================================================

-- Add new columns to payment_methods table
-- MySQL doesn't support IF NOT EXISTS for columns, so we'll add them directly
-- If they already exist, the migration will fail - we'll handle that by dropping and recreating

ALTER TABLE payment_methods ADD COLUMN type VARCHAR(30) DEFAULT 'MOBILE_MONEY';
ALTER TABLE payment_methods ADD COLUMN icon_url VARCHAR(255) DEFAULT NULL;
ALTER TABLE payment_methods ADD COLUMN description VARCHAR(255) DEFAULT NULL;

-- Populate type for existing rows
UPDATE payment_methods SET type = 'MOBILE_MONEY' WHERE code IN ('ECOCASH', 'ONEMONEY', 'TELECASH', 'OMARI', 'INNBUCKS');
UPDATE payment_methods SET type = 'CARD' WHERE code IN ('VISA', 'MASTERCARD', 'ZIMSWITCH');

-- Populate icon_url
UPDATE payment_methods SET icon_url = '/icons/ecocash.png' WHERE code = 'ECOCASH';
UPDATE payment_methods SET icon_url = '/icons/onemoney.png' WHERE code = 'ONEMONEY';
UPDATE payment_methods SET icon_url = '/icons/telecash.png' WHERE code = 'TELECASH';
UPDATE payment_methods SET icon_url = '/icons/omari.png' WHERE code = 'OMARI';
UPDATE payment_methods SET icon_url = '/icons/innbucks.png' WHERE code = 'INNBUCKS';
UPDATE payment_methods SET icon_url = '/icons/visa.png' WHERE code = 'VISA';
UPDATE payment_methods SET icon_url = '/icons/mastercard.png' WHERE code = 'MASTERCARD';
UPDATE payment_methods SET icon_url = '/icons/zimswitch.png' WHERE code = 'ZIMSWITCH';

-- Populate description
UPDATE payment_methods SET description = 'Pay using your EcoCash wallet — you will receive a USSD prompt' WHERE code = 'ECOCASH';
UPDATE payment_methods SET description = 'Pay using your OneMoney wallet — you will receive a USSD prompt' WHERE code = 'ONEMONEY';
UPDATE payment_methods SET description = 'Pay using your Telecash wallet — you will receive a USSD prompt' WHERE code = 'TELECASH';
UPDATE payment_methods SET description = 'Pay using your Omari wallet' WHERE code = 'OMARI';
UPDATE payment_methods SET description = 'Pay using your InnBucks wallet' WHERE code = 'INNBUCKS';
UPDATE payment_methods SET description = 'Pay with Visa' WHERE code = 'VISA';
UPDATE payment_methods SET description = 'Pay with Mastercard' WHERE code = 'MASTERCARD';
UPDATE payment_methods SET description = 'Pay with your ZimSwitch-enabled bank card' WHERE code = 'ZIMSWITCH';