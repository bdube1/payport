-- Owner, 2026-10-25: customers see "Merchant Payment", not "Merchant Checkout".
UPDATE product_types SET name = 'Merchant Payment' WHERE code IN ('MERCHANT_CHECKOUT', 'MERCHANT_CHECKOUT_ZWG');
UPDATE service_providers SET name = 'Merchant Payment' WHERE code IN ('MERCHANT', 'MERCHANT_CHECKOUT', 'MERCHANT_CHECKOUT_ZWG');
