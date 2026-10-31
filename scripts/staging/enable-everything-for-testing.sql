-- STAGING ONLY: switches every service, channel, product, payment method, currency and network on, so the whole
-- platform can be tested end to end before production. Run through enable-everything-for-testing.sh (it refuses
-- anything that is not the staging database). Settlement automation and live payouts are NOT touched: they are
-- environment settings, and staging keeps SETTLEMENT_MODE=PARALLEL with automation off.
SET @by = 'staging:enable-everything';

UPDATE service_lines      SET enabled = TRUE, licence_status = IF(licence_status IN ('GRANTED','NOT_REQUIRED'), licence_status, 'NOT_REQUIRED'),
                              harvest_status = 'BUILT', updated_by = @by, updated_at = NOW(6);
UPDATE platform_channels  SET enabled = TRUE, reason = NULL, updated_by = @by, updated_at = NOW(6);
UPDATE service_providers  SET is_active = TRUE;
UPDATE product_types      SET is_active = TRUE;
UPDATE payment_methods    SET is_active = TRUE;

-- Every market and every currency on and live.
UPDATE markets            SET status = 'LIVE', updated_by = @by, updated_at = NOW(6);
UPDATE market_currencies  SET enabled = TRUE, live = TRUE, updated_by = @by, updated_at = NOW(6);
UPDATE ref_currencies     SET enabled = TRUE WHERE code IN (SELECT currency FROM market_currencies);

-- Every product sold in USD and ZWG.
INSERT INTO product_currencies (product_type_id, currency, is_active, label, display_order, updated_by)
SELECT p.id, c.ccy, TRUE, c.label, c.ord, @by
  FROM product_types p
  JOIN (SELECT 'USD' AS ccy, 'USD $' AS label, 0 AS ord UNION ALL SELECT 'ZWG', 'ZWG', 1) c
 WHERE NOT EXISTS (SELECT 1 FROM product_currencies x WHERE x.product_type_id = p.id AND x.currency = c.ccy);
UPDATE product_currencies SET is_active = TRUE;

-- Every merchant enabled for every market currency.
INSERT IGNORE INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at)
SELECT m.id, mc.country, mc.currency, 'ENABLED', @by, NOW(6) FROM merchants m CROSS JOIN market_currencies mc;
UPDATE merchant_enablements SET status = 'ENABLED';

-- USSD on every network with Payport's short code. Each network still needs its access key or allowed IPs
-- (Platform setup → USSD & WhatsApp) before it accepts traffic.
UPDATE ussd_networks SET enabled = TRUE, short_code = '*699#', updated_by = @by, updated_at = NOW(6);

SELECT 'service lines on'   AS item, COUNT(*) AS n FROM service_lines WHERE enabled
UNION ALL SELECT 'channels on', COUNT(*) FROM platform_channels WHERE enabled
UNION ALL SELECT 'products on', COUNT(*) FROM product_types WHERE is_active
UNION ALL SELECT 'payment methods on', COUNT(*) FROM payment_methods WHERE is_active
UNION ALL SELECT 'currencies live', COUNT(*) FROM market_currencies WHERE live
UNION ALL SELECT 'ussd networks on', COUNT(*) FROM ussd_networks WHERE enabled;
