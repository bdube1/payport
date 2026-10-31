-- Owner, 31 Oct 2026: the name is written "Payport" (not "PayPort") everywhere people read it.
UPDATE users SET full_name = REPLACE(full_name, 'PayPort', 'Payport');
UPDATE merchants SET business_name = REPLACE(business_name, 'PayPort', 'Payport');
UPDATE ref_legal_entities SET legal_name = REPLACE(legal_name, 'PayPort', 'Payport');
UPDATE ledger_categories SET description = REPLACE(description, 'PayPort', 'Payport');
UPDATE compliance_parameters SET description = REPLACE(description, 'PayPort', 'Payport'), source = REPLACE(source, 'PayPort', 'Payport');
UPDATE risk_rules SET source = REPLACE(source, 'PayPort', 'Payport');
UPDATE market_currencies SET bank_account_name = REPLACE(bank_account_name, 'PayPort', 'Payport');
UPDATE service_lines SET name = REPLACE(name, 'PayPort', 'Payport'), description = REPLACE(description, 'PayPort', 'Payport'),
       licence_question = REPLACE(licence_question, 'PayPort', 'Payport');
UPDATE payment_methods SET name = REPLACE(name, 'PayPort', 'Payport'), description = REPLACE(description, 'PayPort', 'Payport');
UPDATE service_providers SET name = REPLACE(name, 'PayPort', 'Payport');
UPDATE platform_channels SET name = REPLACE(name, 'PayPort', 'Payport'), description = REPLACE(description, 'PayPort', 'Payport');
