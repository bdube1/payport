-- Owner, 31 Oct 2026: the store on every PayPort channel. Which channel each order came from (for the merchant's reports).
ALTER TABLE orders ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT 'WEB';
ALTER TABLE orders ADD CONSTRAINT chk_order_channel CHECK (channel IN ('WEB','APP','WHATSAPP','USSD'));
CREATE INDEX idx_orders_phone ON orders (customer_phone);
