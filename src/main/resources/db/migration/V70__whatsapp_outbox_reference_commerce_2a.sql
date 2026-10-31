-- WhatsApp payment results are sent once per payment (owner, 2026-10-31: WhatsApp waits for the answer like the app).
ALTER TABLE whatsapp_outbox ADD COLUMN reference VARCHAR(40) NULL;
CREATE INDEX idx_whatsapp_outbox_reference ON whatsapp_outbox (reference, kind);
