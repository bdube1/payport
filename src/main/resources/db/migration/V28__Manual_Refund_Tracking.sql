-- Manual refund tracking: ops enters a reference for a refund done
-- outside the system (bank transfer, EcoCash, OneMoney, Innbucks, Omari)
-- rather than an automatic provider-side reversal.
ALTER TABLE transactions ADD COLUMN refund_reference VARCHAR(255);
ALTER TABLE transactions ADD COLUMN refund_method VARCHAR(50);
ALTER TABLE transactions ADD COLUMN refunded_at DATETIME;
