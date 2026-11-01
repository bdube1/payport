-- The provider's own reason for a failed or unclear payment, shown to the customer on every channel.
ALTER TABLE order_attempts ADD COLUMN reason VARCHAR(300) NULL;
ALTER TABLE orders ADD COLUMN payment_reason VARCHAR(300) NULL;
