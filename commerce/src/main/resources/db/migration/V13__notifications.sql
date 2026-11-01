-- Owner, 1 Nov 2026: the notification bell. New orders and reviews count as unread until the merchant opens the bell.
ALTER TABLE merchants ADD COLUMN bell_seen_at DATETIME(6) NULL;
