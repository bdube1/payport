-- Owner instruction (2026-10-23): PayPort is licensed for every product line. Lines and channels are
-- switched on and off directly by an administrator (audited); there is no licence step.
ALTER TABLE service_lines DROP CHECK chk_svl_enabled;

-- Display name of the Zambia entity (internal placeholder text removed).
UPDATE ref_legal_entities SET legal_name = 'PayPort Zambia' WHERE code = 'PAYPORT_ZM_SANDBOX';
