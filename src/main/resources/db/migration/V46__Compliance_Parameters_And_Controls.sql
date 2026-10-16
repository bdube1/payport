-- =====================================================================
-- Compliance parameters, approved-only enforcement, tamper-evident audit, dispute profiles.
--
-- Every parameter carries its SOURCE and a STATUS:
--   SOURCED      taken from a cited Zimbabwean regulation or guideline; compliance must still confirm
--                it is the current applicable version
--   PROVISIONAL  international best practice or an informed placeholder; must be confirmed
--   CONFIRMED    confirmed by two compliance officers (the only way to reach this status)
-- =====================================================================

CREATE TABLE compliance_parameters (
    param_key     VARCHAR(80)    NOT NULL PRIMARY KEY,
    category      VARCHAR(30)    NOT NULL,
    value_json    TEXT           NOT NULL,
    unit          VARCHAR(40)    NOT NULL,
    description   VARCHAR(500)   NOT NULL,
    source        VARCHAR(600)   NOT NULL,
    status        VARCHAR(12)    NOT NULL,
    version       INT            NOT NULL DEFAULT 1,
    updated_by    VARCHAR(120)   NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    confirmed_by  VARCHAR(250)   NULL,
    confirmed_at  DATETIME(6)    NULL,
    CONSTRAINT chk_cp_status CHECK (status IN ('PROVISIONAL','SOURCED','CONFIRMED')),
    CONSTRAINT chk_cp_confirmed CHECK (status <> 'CONFIRMED' OR confirmed_by IS NOT NULL)
);

INSERT INTO compliance_parameters (param_key, category, value_json, unit, description, source, status, updated_by, updated_at) VALUES
-- Disputes and complaints (RBZ Consumer Protection Framework No.1-2017/BSD)
('DISPUTE.ACK_HOURS', 'DISPUTES', '48', 'hours', 'Acknowledge a complaint in writing (electronic allowed)', 'RBZ Consumer Protection Framework No.1-2017/BSD s6.1.8(a): within 48 hours of receipt', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('DISPUTE.RESOLVE_WORKING_DAYS', 'DISPUTES', '10', 'working days', 'Investigate and resolve a complaint', 'RBZ Consumer Protection Framework No.1-2017/BSD s6.1.8(c): within 10 working days of receipt', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('DISPUTE.PROGRESS_UPDATE_DAYS', 'DISPUTES', '7', 'days', 'When more time is needed: progress updates to the complainant at most this far apart', 'RBZ Consumer Protection Framework s6.1.9(b): intervals not greater than seven (7) days', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('DISPUTE.OUTCOME_NOTICE_DAYS', 'DISPUTES', '7', 'days', 'Tell the complainant the outcome after the investigation is complete', 'RBZ Consumer Protection Framework s6.1.9(c): within seven (7) days of completing the investigation', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('DISPUTE.ERROR_RESOLVE_HOURS', 'DISPUTES', '48', 'hours', 'Resolve an error affecting customers (e.g. wrong debit, failed reversal)', 'RBZ Consumer Protection Framework s6.5.4: no later than 48 hours after the error was first discovered', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('DISPUTE.ERROR_RBZ_NOTICE_DAYS', 'DISPUTES', '14', 'days', 'An error not resolved within the error deadline must be reported to the RBZ in writing', 'RBZ Consumer Protection Framework s6.5.5: in writing within fourteen (14) days', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('DISPUTE.CHARGEBACK_RESPONSE_DAYS', 'DISPUTES', '30', 'days', 'Merchant/acquirer response to a card chargeback', 'International card-scheme practice (typically 30 days for representment); confirm with ZimSwitch and scheme rules', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
('DISPUTE.RECORD_RETENTION_YEARS', 'DISPUTES', '2', 'years', 'Keep complaint records at least', 'RBZ Consumer Protection Framework s6.3.5: minimum of two years', 'SOURCED', 'MIGRATION:V46', NOW(6)),
-- AML/CFT (RBZ AML/CFT/CPF Guideline No: 01-2025/BSSFS, MLPC Act [Chapter 9:24])
('STR.DEADLINE_WORKING_DAYS', 'AML', '3', 'working days', 'File a suspicious transaction report with the FIU after forming the suspicion', 'RBZ AML/CFT/CPF Guideline 01-2025 s3.105: no later than three (3) working days after forming the suspicion; via goAML (s3.106)', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('CTR.THRESHOLD_MINOR', 'AML', '{"USD":500000,"ZWG":13500000}', 'minor units', 'Cash/threshold transaction report: single or aggregated transactions at or above', 'MLPC Act s30(6) and FIU directives (Guideline 01-2025 s3.133-3.134). US$5,000 with 24-hour aggregation is the FIU threshold for casinos (FIU AML/CFT Guidelines for Casinos 2015 s5); confirm the directive for payment service providers. ZWG equivalent is a placeholder.', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
('CTR.AGGREGATION_HOURS', 'AML', '24', 'hours', 'Window for aggregating one customer''s transactions towards the threshold', 'FIU AML/CFT Guidelines for Casinos 2015 s5.2 (two or more transactions by the same customer within 24 hours); confirm for payment service providers', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
('CDD.OCCASIONAL_THRESHOLD_MINOR', 'AML', '{"USD":500000,"ZWG":13500000}', 'minor units', 'Identify an occasional (unregistered) customer for a transaction at or above', 'RBZ AML/CFT/CPF Guideline 01-2025 s3.36(b): occasional transaction valued at US$5,000 or more. ZWG equivalent is a placeholder.', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('WIRE.ORIGINATOR_INFO_THRESHOLD_MINOR', 'AML', '{"USD":100000}', 'minor units', 'Full originator/beneficiary information on transfers at or above', 'RBZ AML/CFT/CPF Guideline 01-2025 s3.36(c), s3.147: US$1,000 or more', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('AML.RECORD_RETENTION_YEARS', 'AML', '5', 'years', 'Keep CDD, transaction, internal and FIU report records at least', 'RBZ AML/CFT/CPF Guideline 01-2025 s3.141 (five years); MLPC Act s24', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('PAYMENTS.RECORD_RETENTION_YEARS', 'PAYMENTS', '10', 'years', 'Keep payment system records at least', 'RBZ Guidelines for Retail Payment Systems and Instruments 2017 s9.2: minimum ten years', 'SOURCED', 'MIGRATION:V46', NOW(6)),
('CASE.REVIEW_WORKING_DAYS', 'AML', '2', 'working days', 'Compliance review of an alert/case so that an STR can still be filed in time', 'Internal policy derived from the 3-working-day STR deadline (Guideline 01-2025 s3.105); PayPort choice', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
-- Screening
('SCREENING.MIN_SCORE', 'SCREENING', '0.88', 'score 0-1', 'Name similarity at or above which a possible match is raised', 'Industry practice for fuzzy name screening (Jaro-Winkler 0.85-0.90); tune with false-positive data', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
('SCREENING.LOW_QUALITY_ALIAS_PENALTY', 'SCREENING', '0.05', 'score', 'Subtracted from matches on aliases the UN marks Low quality', 'UN Security Council Consolidated List notes on alias quality; PayPort choice', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
('SCREENING.MAX_LIST_AGE_HOURS', 'SCREENING', '24', 'hours', 'Screening counts as complete only if the list was successfully checked within', 'Guideline 01-2025 s3.83: lists updated immediately upon any new designation; PayPort refreshes hourly and treats a list unchecked for longer as outdated', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
('SCREENING.MAX_ENTRY_DROP_PERCENT', 'SCREENING', '10', 'percent', 'A new list version with this many fewer entries is quarantined (possible truncated or tampered file)', 'PayPort integrity control', 'PROVISIONAL', 'MIGRATION:V46', NOW(6)),
-- Calendar
('CALENDAR.ZW.HOLIDAYS', 'CALENDAR', '["2026-01-01","2026-02-21","2026-04-03","2026-04-04","2026-04-06","2026-04-18","2026-05-01","2026-05-25","2026-08-10","2026-08-11","2026-12-22","2026-12-25","2026-12-26"]', 'dates', 'Zimbabwe public holidays (not working days)', 'Public Holidays and Prohibition of Business Act [Chapter 10:21]; dates must be checked against the Government Gazette each year', 'PROVISIONAL', 'MIGRATION:V46', NOW(6));

-- Rules: provenance, and enforcement only for approved definitions.
ALTER TABLE risk_rules
    ADD COLUMN source VARCHAR(600) NULL,
    ADD COLUMN param_status VARCHAR(12) NOT NULL DEFAULT 'PROVISIONAL',
    ADD COLUMN approved_by VARCHAR(250) NULL,
    ADD COLUMN approved_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_rr_enforce_approved CHECK (mode <> 'ENFORCE' OR approved_by IS NOT NULL);

UPDATE risk_rules SET source = 'PayPort transaction-monitoring baseline (FATF risk-based approach); thresholds to be calibrated by compliance' WHERE source IS NULL;

INSERT INTO risk_rules (code, name, rule_type, params, mode, action, severity, updated_by, updated_at, source, param_status) VALUES
('TX_LIMIT', 'Single payment above the limit', 'TX_LIMIT', '{"maxMinor":{"USD":500000,"ZWG":13500000}}', 'OBSERVE', 'BLOCK', 'MEDIUM', 'MIGRATION:V46', NOW(6),
 'RBZ Guidelines for Retail Payment Systems 2017 s22.1 (RBZ may impose limits); load the current RBZ limits for each instrument', 'PROVISIONAL'),
('DAILY_LIMIT', 'Customer daily total above the limit', 'PERIOD_LIMIT', '{"periodHours":24,"maxMinor":{"USD":1000000,"ZWG":27000000}}', 'OBSERVE', 'BLOCK', 'MEDIUM', 'MIGRATION:V46', NOW(6),
 'RBZ Guidelines for Retail Payment Systems 2017 s22.1; load the current RBZ daily limits', 'PROVISIONAL'),
('MONTHLY_LIMIT', 'Customer 30-day total above the limit', 'PERIOD_LIMIT', '{"periodHours":720,"maxMinor":{"USD":5000000,"ZWG":135000000}}', 'OBSERVE', 'BLOCK', 'MEDIUM', 'MIGRATION:V46', NOW(6),
 'RBZ Guidelines for Retail Payment Systems 2017 s22.1; load the current RBZ monthly limits', 'PROVISIONAL'),
('CDD_OCCASIONAL', 'Unregistered customer at or above the identification threshold', 'CDD_THRESHOLD', '{"parameter":"CDD.OCCASIONAL_THRESHOLD_MINOR"}', 'OBSERVE', 'BLOCK', 'HIGH', 'MIGRATION:V46', NOW(6),
 'RBZ AML/CFT/CPF Guideline 01-2025 s3.36(b): identify occasional customers at US$5,000 or more', 'SOURCED');

-- Tamper-evident compliance audit trail: each row carries the hash of the previous one.
CREATE TABLE compliance_audit_log (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    at         DATETIME(6)   NOT NULL,
    actor      VARCHAR(120)  NOT NULL,
    action     VARCHAR(60)   NOT NULL,
    subject    VARCHAR(200)  NOT NULL,
    details    TEXT          NULL,
    prev_hash  CHAR(64)      NOT NULL,
    hash       CHAR(64)      NOT NULL,
    INDEX idx_cal_subject (subject),
    INDEX idx_cal_at (at)
);
CREATE TABLE compliance_audit_head (
    id         TINYINT       NOT NULL PRIMARY KEY,
    last_hash  CHAR(64)      NOT NULL
);
INSERT INTO compliance_audit_head (id, last_hash) VALUES (1, '0000000000000000000000000000000000000000000000000000000000000000');

-- Disputes: type (profile), progress updates and outcome notice.
ALTER TABLE disputes
    ADD COLUMN dispute_type VARCHAR(12) NOT NULL DEFAULT 'COMPLAINT',
    ADD COLUMN next_update_due_at DATETIME NULL,
    ADD COLUMN outcome_notified_at DATETIME NULL,
    ADD COLUMN rbz_notified_at DATETIME NULL,
    ADD CONSTRAINT chk_disp_type CHECK (dispute_type IN ('COMPLAINT','ERROR','CHARGEBACK'));

CREATE TABLE dispute_updates (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dispute_id  BIGINT        NOT NULL,
    kind        VARCHAR(20)   NOT NULL,
    note        VARCHAR(2000) NOT NULL,
    by_user     VARCHAR(120)  NOT NULL,
    at          DATETIME(6)   NOT NULL,
    CONSTRAINT chk_du_kind CHECK (kind IN ('ACKNOWLEDGEMENT','PROGRESS_UPDATE','OUTCOME_NOTICE','RBZ_NOTICE')),
    INDEX idx_du_dispute (dispute_id)
);

-- Who may approve FIU reports (designated by an admin request and a compliance officer's approval).
CREATE TABLE compliance_authorities (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    holder      VARCHAR(120)  NOT NULL,
    authority   VARCHAR(30)   NOT NULL,
    granted_by  VARCHAR(250)  NOT NULL,
    granted_at  DATETIME(6)   NOT NULL,
    revoked_at  DATETIME(6)   NULL,
    revoked_by  VARCHAR(120)  NULL,
    CONSTRAINT chk_ca_authority CHECK (authority IN ('REPORT_APPROVER')),
    INDEX idx_ca_holder (holder, authority)
);

-- Structuring and large-transaction rules read the reporting threshold from one parameter.
UPDATE risk_rules SET params = '{"windowMinutes":1440,"parameter":"CTR.THRESHOLD_MINOR","lowerRatio":0.8,"minCount":3}',
    source = 'FATF structuring typology; threshold = CTR.THRESHOLD_MINOR' WHERE code = 'STRUCTURING_24H';
UPDATE risk_rules SET params = '{"parameter":"CTR.THRESHOLD_MINOR"}',
    source = 'MLPC Act s30(6) threshold reporting; threshold = CTR.THRESHOLD_MINOR' WHERE code = 'LARGE_TRANSACTION';
UPDATE risk_rules SET params = '{"minScore":"SCREENING.MIN_SCORE"}', source = 'UNSC resolutions / SI 110 of 2021; RBZ Guideline 01-2025 s3.80-3.83' WHERE code = 'SANCTIONS_CUSTOMER';
