-- =====================================================================
-- R1 exposure report: sessions whose payment was SIMULATED (no real provider
-- call) but which were marked PAID, and whether any were settled.
--
-- Read-only. Run against production (or a fresh copy) AFTER V32 is applied:
--   mysql -h <host> -u <readonly user> -p <db> < r1-exposure-report.sql
-- Share the output with finance before deciding any recovery action.
-- =====================================================================

-- 1. Summary per merchant and currency: simulated "paid" sessions, and how many were settled.
SELECT cs.merchant_id,
       m.business_name,
       cs.currency,
       COUNT(*)                                                        AS simulated_paid_sessions,
       SUM(cs.amount_cents)                                            AS simulated_gross_cents,
       SUM(CASE WHEN cs.settlement_id IS NOT NULL THEN 1 ELSE 0 END)    AS settled_sessions,
       SUM(CASE WHEN cs.settlement_id IS NOT NULL THEN COALESCE(cs.net_amount_cents, cs.amount_cents) ELSE 0 END)
                                                                       AS settled_net_cents_at_risk,
       MIN(cs.created_at)                                              AS first_seen,
       MAX(cs.created_at)                                              AS last_seen
FROM checkout_sessions cs
JOIN transactions t ON t.reference = cs.transaction_reference
LEFT JOIN merchants m ON m.id = cs.merchant_id
WHERE t.provider_reference LIKE 'TEST\_SIM\_%'
  AND cs.status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')
GROUP BY cs.merchant_id, m.business_name, cs.currency
ORDER BY settled_net_cents_at_risk DESC, simulated_gross_cents DESC;

-- 2. Detail of the ones that matter most: simulated, created without a TEST key
--    (i.e. payment links / dashboard checkouts - the R1 defect), with settlement status.
SELECT cs.id                AS session_id,
       cs.merchant_id,
       cs.reference         AS merchant_reference,
       cs.metadata,
       cs.amount_cents,
       cs.currency,
       cs.status            AS session_status,
       cs.customer_phone,
       cs.created_at,
       t.reference          AS transaction_reference,
       t.provider_reference,
       t.status             AS transaction_status,
       cs.settlement_id,
       s.status             AS settlement_status,
       s.payout_reference
FROM checkout_sessions cs
JOIN transactions t ON t.reference = cs.transaction_reference
LEFT JOIN api_keys k ON k.id = cs.api_key_id
LEFT JOIN settlements s ON s.id = cs.settlement_id
WHERE t.provider_reference LIKE 'TEST\_SIM\_%'
  AND cs.status IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED')
  AND (cs.api_key_id IS NULL OR k.id IS NULL OR k.environment <> 'TEST')
ORDER BY cs.created_at;

-- 3. Check of the V32 backfill: no simulated session may be livemode = TRUE,
--    and no session created with a TEST key may be livemode = TRUE. Expect 0 rows.
SELECT cs.id, cs.livemode, cs.api_key_id, k.environment, t.provider_reference
FROM checkout_sessions cs
LEFT JOIN api_keys k ON k.id = cs.api_key_id
LEFT JOIN transactions t ON t.reference = cs.transaction_reference
WHERE cs.livemode = TRUE
  AND ((t.provider_reference LIKE 'TEST\_SIM\_%') OR k.environment = 'TEST');
