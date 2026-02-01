-- Add snapshot_group_id column to alert_history table
-- This column groups alerts from the same market snapshot across multiple strategies

ALTER TABLE alert_history ADD COLUMN IF NOT EXISTS snapshot_group_id VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_alert_history_snapshot ON alert_history(snapshot_group_id);
