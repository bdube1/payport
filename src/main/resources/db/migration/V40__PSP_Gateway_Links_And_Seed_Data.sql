-- =====================================================================
-- PSP: links into the existing gateway tables, plus starting configuration.
--
-- Every number seeded here is a PLACEHOLDER: KYC tier limits, tariffs, routing costs,
-- and fraud thresholds must be set to your RBZ licence conditions and your commercial
-- agreements with each rail before go-live. They are all editable at runtime through
-- the admin APIs (four-eyes approved where they affect money).
-- =====================================================================

-- ── 1. Gateway table changes ──────────────────────────────────────────

-- Gateway refunds now link to the PSP refund that funded them from the merchant's ledger balance.
ALTER TABLE refunds ADD COLUMN psp_refund_ref VARCHAR(40) NULL;
CREATE INDEX idx_refunds_psp_ref ON refunds (psp_refund_ref);

-- Cutover: legacy settlement rows whose sessions were carried into the ledger as opening balances.
ALTER TABLE settlements DROP CHECK chk_settlement_status;
ALTER TABLE settlements ADD CONSTRAINT chk_settlement_status
    CHECK (status IN ('PENDING','PROCESSING','PAID','MIGRATED_TO_LEDGER'));

-- Wallet top-ups go through the gateway's hardened payment pipeline as a product.
INSERT IGNORE INTO service_providers (code, name, provider_type, is_active)
VALUES ('PAYPORT_WALLET', 'PayPort Wallet', 'WALLET', TRUE);

INSERT IGNORE INTO product_types (code, name, category, provider_code, is_active, min_amount_cents, max_amount_cents, currency)
VALUES ('WALLET_TOPUP',     'Wallet Top-up',       'WALLET', 'PAYPORT_WALLET', TRUE, 100, 100000000, 'USD'),
       ('WALLET_TOPUP_ZWG', 'Wallet Top-up (ZiG)', 'WALLET', 'PAYPORT_WALLET', TRUE, 100, 100000000, 'ZWG');

-- ── 2. Smart routing (costs/bands are placeholders: set to your contracts) ─

INSERT INTO psp_routing_rules (instrument, currency, provider, priority, weight, min_amount_minor, max_amount_minor,
                               cost_bps, cost_fixed_minor, network_code, enabled, notes, created_at, updated_at) VALUES
('MOBILE_WALLET','USD','ECOCASH', 10,100,1,100000000,200,0,'ECONET', TRUE,'EcoCash for Econet numbers',NOW(6),NOW(6)),
('MOBILE_WALLET','ZWG','ECOCASH', 10,100,1,100000000,200,0,'ECONET', TRUE,'EcoCash for Econet numbers',NOW(6),NOW(6)),
('MOBILE_WALLET','USD','ONEMONEY',10,100,1,100000000,200,0,'NETONE', TRUE,'OneMoney for NetOne numbers',NOW(6),NOW(6)),
('MOBILE_WALLET','ZWG','ONEMONEY',10,100,1,100000000,200,0,'NETONE', TRUE,'OneMoney for NetOne numbers',NOW(6),NOW(6)),
('MOBILE_WALLET','USD','TELECASH',10,100,1,100000000,200,0,'TELECEL',FALSE,'Enable once the Telecash collection API is live',NOW(6),NOW(6)),
('MOBILE_WALLET','ZWG','TELECASH',10,100,1,100000000,200,0,'TELECEL',FALSE,'Enable once the Telecash collection API is live',NOW(6),NOW(6)),
('MOBILE_WALLET','USD','OMARI',   50,100,1,100000000,150,0,NULL,     TRUE,'O''mari: any network, used when chosen explicitly',NOW(6),NOW(6)),
('MOBILE_WALLET','ZWG','OMARI',   50,100,1,100000000,150,0,NULL,     TRUE,'O''mari: any network, used when chosen explicitly',NOW(6),NOW(6)),
('CARD',         'USD','ZIMSWITCH',10,100,1,100000000,250,0,NULL,    TRUE,'ZimSwitch card acquiring',NOW(6),NOW(6)),
('CARD',         'ZWG','ZIMSWITCH',10,100,1,100000000,250,0,NULL,    TRUE,'ZimSwitch card acquiring',NOW(6),NOW(6)),
('PAYOUT_BANK',  'USD','ZIPIT',   10,100,1,500000,0,50,NULL,         TRUE,'Instant interbank; band ceiling is a placeholder',NOW(6),NOW(6)),
('PAYOUT_BANK',  'ZWG','ZIPIT',   10,100,1,500000000,0,500,NULL,     TRUE,'Instant interbank; band ceiling is a placeholder',NOW(6),NOW(6)),
('PAYOUT_BANK',  'USD','RTGS',    20,100,1,999999999999,0,500,NULL,  TRUE,'ZIPSS RTGS for amounts above the ZIPIT band',NOW(6),NOW(6)),
('PAYOUT_BANK',  'ZWG','RTGS',    20,100,1,999999999999,0,5000,NULL, TRUE,'ZIPSS RTGS for amounts above the ZIPIT band',NOW(6),NOW(6));

-- ── 3. Fraud rules (USD-equivalent thresholds; tune on live data) ──────

INSERT INTO psp_risk_rules (rule_code, description, rule_type, applies_to, window_minutes, threshold_count,
                            threshold_amount_usd_minor, score, action, enabled, updated_at) VALUES
('LARGE_SINGLE',     'Single transaction of USD 1,000 or more',                    'SINGLE_AMOUNT',           '*',                          0,    0, 100000, 30,'SCORE', TRUE,NOW(6)),
('VERY_LARGE_SINGLE','Single transaction of USD 5,000 or more: always reviewed',   'SINGLE_AMOUNT',           '*',                          0,    0, 500000, 40,'REVIEW',TRUE,NOW(6)),
('VELOCITY_1H',      'More than 10 debits in an hour',                             'VELOCITY_COUNT',          'P2P,WITHDRAWAL,CASH_OUT,WALLET_MERCHANT_PAYMENT',60,10,0,30,'SCORE',TRUE,NOW(6)),
('VALUE_24H',        'More than USD 3,000 out in 24 hours',                        'VELOCITY_AMOUNT',         'P2P,WITHDRAWAL,CASH_OUT',    1440, 0, 300000, 30,'SCORE', TRUE,NOW(6)),
('NEW_PAYEE_LARGE',  'USD 200+ to a counterparty never paid before',               'NEW_COUNTERPARTY_AMOUNT', 'P2P,WITHDRAWAL',             0,    0, 20000,  25,'SCORE', TRUE,NOW(6)),
('NIGHT_LARGE',      'USD 200+ between 23:00 and 05:00',                           'NIGHT_AMOUNT',            'P2P,WITHDRAWAL,CASH_OUT',    0,    0, 20000,  15,'SCORE', TRUE,NOW(6)),
('FAN_OUT_1H',       'Paying more than 8 different people in an hour (mule sign)', 'DISTINCT_COUNTERPARTIES', 'P2P',                        60,   8, 0,      40,'SCORE', TRUE,NOW(6)),
('AGENT_CASH_BURST', 'More than 30 agent cash transactions in an hour',            'VELOCITY_COUNT',          'CASH_IN,CASH_OUT',           60,  30, 0,      30,'REVIEW',TRUE,NOW(6));

-- ── 4. KYC tier limits (PLACEHOLDERS: set to your RBZ e-money licence conditions) ─
-- USD in cents; ZWG in ZiG cents.

INSERT INTO psp_kyc_tier_limits (kyc_tier, currency, max_balance_minor, max_txn_minor, daily_debit_minor, monthly_debit_minor, daily_count, updated_at) VALUES
('TIER1','USD',   30000,   10000,   20000,    100000,  10, NOW(6)),
('TIER2','USD',  300000,  100000,  150000,   1000000,  30, NOW(6)),
('TIER3','USD', 2000000,  500000, 1000000,  10000000, 100, NOW(6)),
('TIER1','ZWG',  900000,  300000,  600000,   3000000,  10, NOW(6)),
('TIER2','ZWG', 9000000, 3000000, 4500000,  30000000,  30, NOW(6)),
('TIER3','ZWG',60000000,15000000,30000000, 300000000, 100, NOW(6));

-- ── 5. Wallet tariffs (PLACEHOLDERS; IMTT is applied separately from psp_tax_rates) ─

INSERT INTO psp_wallet_tariffs (operation, currency, band_min_minor, band_max_minor, customer_fee_fixed_minor, customer_fee_bps,
                                agent_commission_fixed_minor, agent_commission_bps, status, approved_by, created_at) VALUES
('P2P',       'USD',     1,     1000,  2,   0, 0,  0,'ACTIVE','SYSTEM:seed',NOW(6)),
('P2P',       'USD',  1001,    10000,  0, 100, 0,  0,'ACTIVE','SYSTEM:seed',NOW(6)),
('P2P',       'USD', 10001,999999999,  0,  75, 0,  0,'ACTIVE','SYSTEM:seed',NOW(6)),
('CASH_OUT',  'USD',     1,     2000, 20,   0,10,  0,'ACTIVE','SYSTEM:seed',NOW(6)),
('CASH_OUT',  'USD',  2001,999999999,  0, 200, 0,100,'ACTIVE','SYSTEM:seed',NOW(6)),
('CASH_IN',   'USD',     1,999999999,  0,   0, 0, 50,'ACTIVE','SYSTEM:seed',NOW(6)),
('WITHDRAWAL','USD',     1,999999999, 50,  50, 0,  0,'ACTIVE','SYSTEM:seed',NOW(6)),
('P2P',       'ZWG',     1,    30000, 50,   0, 0,  0,'ACTIVE','SYSTEM:seed',NOW(6)),
('P2P',       'ZWG', 30001,999999999,  0, 100, 0,  0,'ACTIVE','SYSTEM:seed',NOW(6)),
('CASH_OUT',  'ZWG',     1,999999999,  0, 200, 0,100,'ACTIVE','SYSTEM:seed',NOW(6)),
('CASH_IN',   'ZWG',     1,999999999,  0,   0, 0, 50,'ACTIVE','SYSTEM:seed',NOW(6)),
('WITHDRAWAL','ZWG',     1,999999999,1500, 50, 0,  0,'ACTIVE','SYSTEM:seed',NOW(6));

-- ── 6. Notification templates (English; edit via the admin API without a release) ─

INSERT INTO psp_notification_templates (code, channel, locale, subject, body, active, updated_at) VALUES
('WALLET_P2P_PAYER','SMS','en',NULL,'PayPort: You sent {{amount}}. Fee {{fee}}. Ref {{transferRef}}.',TRUE,NOW(6)),
('WALLET_P2P_PAYEE','SMS','en',NULL,'PayPort: You received {{amount}}. Ref {{transferRef}}.',TRUE,NOW(6)),
('WALLET_WALLET_MERCHANT_PAYMENT_PAYER','SMS','en',NULL,'PayPort: You paid {{amount}} to a merchant. Fee {{fee}}. Ref {{transferRef}}.',TRUE,NOW(6)),
('WALLET_WITHDRAWAL_PAYER','SMS','en',NULL,'PayPort: Withdrawal of {{amount}} to your bank is being processed. Fee {{fee}}. Ref {{transferRef}}.',TRUE,NOW(6)),
('CASH_IN_CUSTOMER','SMS','en',NULL,'PayPort: {{amount}} deposited to your wallet by agent {{agentCode}}. Ref {{transferRef}}.',TRUE,NOW(6)),
('CASH_IN_AGENT','SMS','en',NULL,'PayPort agent: cash-in of {{amount}} for {{customerMsisdn}} done. Ref {{transferRef}}.',TRUE,NOW(6)),
('CASH_OUT_CUSTOMER','SMS','en',NULL,'PayPort: You withdrew {{amount}} at agent {{agentCode}}. Ref {{transferRef}}.',TRUE,NOW(6)),
('CASH_OUT_AGENT','SMS','en',NULL,'PayPort agent: pay out {{amount}} cash to {{customerMsisdn}}. Ref {{transferRef}}.',TRUE,NOW(6)),
('PAYMENT_VERIFYING','SMS','en',NULL,'PayPort: We are confirming your payment {{reference}} of {{amount}} with {{provider}}. Please do not pay again; we will update you.',TRUE,NOW(6)),
('SETTLEMENT_PAID','EMAIL','en','PayPort settlement paid: {{amount}}','Hello {{merchantName}},\n\nYour settlement {{instructionRef}} of {{amount}} has been paid to {{destination}}.\nBank reference: {{payoutReference}}.\n\nPayPort Finance',TRUE,NOW(6)),
('SETTLEMENT_FAILED','EMAIL','en','PayPort settlement could not be paid','Hello {{merchantName}},\n\nSettlement {{instructionRef}} could not be paid: {{reason}}.\nThe funds are back in your available balance and will be included in the next settlement once the issue is fixed. Please check your payout account details on the dashboard.\n\nPayPort Finance',TRUE,NOW(6)),
('MERCHANT_SUSPENDED','EMAIL','en','Your PayPort account has been restricted','Hello {{merchantName}},\n\nYour account has been restricted ({{scope}}). Reason: {{reason}}.\nPlease contact PayPort support to resolve this.\n\nPayPort Compliance',TRUE,NOW(6)),
('DISPUTE_OPENED','EMAIL','en','Dispute opened: {{amount}}','Hello {{merchantName}},\n\nA customer has disputed a payment of {{amount}} (dispute {{disputeId}}). The amount is on hold.\nPlease respond with evidence by {{respondBy}} from your dashboard.\n\nPayPort Disputes',TRUE,NOW(6)),
('OPS_RECON_BREAK','EMAIL','en','[PayPort] Recon break {{breakType}} {{breakRef}}','A reconciliation break was opened.\nType: {{breakType}}\nSource: {{source}}\nReference: {{reference}}\nBreak: {{breakRef}}\n\nReview it in the reconciliation desk. Nothing has been corrected automatically.',TRUE,NOW(6)),
('OPS_FRAUD_CASE','EMAIL','en','[PayPort] Fraud case {{caseRef}} ({{decision}})','Fraud case {{caseRef}} opened for {{subjectRef}}. Decision {{decision}}, score {{score}}.',TRUE,NOW(6)),
('COMPLIANCE_AML_ALERT','EMAIL','en','[PayPort] AML alert {{alertRef}} ({{type}})','AML alert {{alertRef}} raised for {{subjectRef}}. Type {{type}}, score {{score}}.\nReview it in the compliance desk; file an STR with the FIU if suspicion is confirmed.',TRUE,NOW(6));

-- Merchant PSP profiles are not seeded here: each one is created on first use from the merchant's
-- onboarding status and risk score (MerchantControlService.profile), with the default products approved.
