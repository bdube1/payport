-- V11__Add_last_status_detail_to_provider_health.sql
ALTER TABLE provider_health ADD COLUMN last_status_detail VARCHAR(500);