-- A merchant's internal database id was never meant to be exposed
-- publicly -- it's sequential, so anyone could guess other applications
-- by incrementing it. This is a real, non-guessable reference generated
-- at signup, given to the applicant to track their own application
-- without needing to log in anywhere.

ALTER TABLE merchants ADD COLUMN application_reference VARCHAR(30) NULL AFTER id;
CREATE UNIQUE INDEX idx_merchants_application_reference ON merchants(application_reference);
