-- Owner request 2026-10-24: a ZimSwitch card is dual-interface and can be linked to both the holder's USD and ZWG
-- wallets (the POS sends the transaction currency); a VISA card is linked to the USD wallet only.
ALTER TABLE wallet_cards
    ADD COLUMN second_wallet_id BIGINT NULL,
    ADD CONSTRAINT fk_card_second_wallet FOREIGN KEY (second_wallet_id) REFERENCES wallets (id);
