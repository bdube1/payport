-- =====================================================
-- V10: Add failure_details column to transactions table
-- =====================================================

ALTER TABLE transactions ADD COLUMN failure_details TEXT DEFAULT NULL;