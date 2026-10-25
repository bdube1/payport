-- Owner, 2026-10-25: each portal and the app sign a person out after this many minutes without activity.
-- Changed from the ops console (Platform Setup → Session Timeouts).
INSERT INTO platform_settings (setting_key, setting_value) VALUES
  ('session_timeout_ops_minutes', '15'),
  ('session_timeout_merchant_minutes', '15'),
  ('session_timeout_agent_minutes', '15'),
  ('session_timeout_consumer_minutes', '10'),
  ('session_timeout_mobile_minutes', '5');
