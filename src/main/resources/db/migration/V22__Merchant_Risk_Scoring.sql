-- Merchants Module, Phase 6: signup risk scoring.
--
-- This is deliberately a prioritization aid for ops, not an auto-approval
-- mechanism. Every application still requires a human decision -- the
-- score just helps ops triage a growing queue by putting the applications
-- most worth a closer look at the top, rather than working strictly in
-- submission order.

ALTER TABLE merchants ADD COLUMN risk_score INT NOT NULL DEFAULT 0 AFTER default_currency;
ALTER TABLE merchants ADD COLUMN risk_flags VARCHAR(1000) AFTER risk_score;
