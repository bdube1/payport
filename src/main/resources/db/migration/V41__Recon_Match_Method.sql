-- How a statement line was matched: REFERENCE (PayPort or provider reference) or AMOUNT_TIME
-- (unique amount + currency + time pairing, for reports without PayPort's reference).
ALTER TABLE recon_statement_lines ADD COLUMN match_method VARCHAR(12) NULL;
