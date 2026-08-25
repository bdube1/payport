-- Add logo_url column to product_types table
ALTER TABLE product_types ADD COLUMN logo_url VARCHAR(255);

-- Update existing products with default logos (optional)
UPDATE product_types SET logo_url = '/logos/c3f.png' WHERE code = 'BILLS_ZESA';
UPDATE product_types SET logo_url = '/logos/a6.png' WHERE code = 'BILLS_DSTV';
UPDATE product_types SET logo_url = '/logos/city-harare.png' WHERE code = 'BILLS_CITY_HARARE';
UPDATE product_types SET logo_url = '/logos/corporate24.png' WHERE code = 'BILLS_CORPORATE24';
UPDATE product_types SET logo_url = '/logos/healthcare.png' WHERE code = 'BILLS_HEALTHCARE';
UPDATE product_types SET logo_url = '/logos/doves.png' WHERE code = 'BILLS_DOVES';
UPDATE product_types SET logo_url = '/logos/e3.jpg' WHERE code = 'BILLS_CITY_BULAWAYO';
UPDATE product_types SET logo_url = '/logos/edgars.png' WHERE code = 'BILLS_EDGARS';
UPDATE product_types SET logo_url = '/logos/nust.png' WHERE code = 'BILLS_NUST';
UPDATE product_types SET logo_url = '/logos/cut.png' WHERE code = 'BILLS_CUT';
UPDATE product_types SET logo_url = '/logos/f44.png' WHERE code = 'BILLS_MOONLIGHT';
UPDATE product_types SET logo_url = '/logos/59.jpg' WHERE code = 'BILLS_NYARADZO';
UPDATE product_types SET logo_url = '/logos/city-gweru.png' WHERE code = 'BILLS_CITY_GWERU';
UPDATE product_types SET logo_url = '/logos/6.jpg' WHERE code = 'BILLS_ZOL';
UPDATE product_types SET logo_url = '/logos/city-mutare.png' WHERE code = 'BILLS_CITY_MUTARE';
UPDATE product_types SET logo_url = '/logos/4b6e.png' WHERE code = 'AIRTIME_ECONET';
UPDATE product_types SET logo_url = '/logos/ab2b88.png' WHERE code = 'AIRTIME_NETONE';
UPDATE product_types SET logo_url = '/logos/ad.png' WHERE code = 'AIRTIME_TELECEL';