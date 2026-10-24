-- Owner request 2026-10-24: the customer charge (and tax) set in Limits & Charges is added to what the
-- customer pays for a product on every payment method (USD 1.00 airtime + USD 0.10 charge = USD 1.10 prompt).
-- amount_cents stays the face value delivered; charge_cents + tax_cents are collected on top.
ALTER TABLE transactions
    ADD COLUMN charge_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN tax_cents BIGINT NOT NULL DEFAULT 0;

-- A card's nickname, shown on the card.
ALTER TABLE wallet_cards ADD COLUMN nickname VARCHAR(40) NULL;
