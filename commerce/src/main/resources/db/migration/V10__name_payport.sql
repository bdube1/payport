-- Owner, 31 Oct 2026: the name is written "Payport" (not "PayPort").
UPDATE merchants SET business_name = REPLACE(business_name, 'PayPort', 'Payport');
UPDATE stores SET name = REPLACE(name, 'PayPort', 'Payport'), about = REPLACE(about, 'PayPort', 'Payport'), tagline = REPLACE(tagline, 'PayPort', 'Payport');
