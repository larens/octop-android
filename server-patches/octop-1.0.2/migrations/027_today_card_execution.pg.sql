ALTER TABLE today_cards
  ADD COLUMN IF NOT EXISTS execution_thread_id TEXT REFERENCES threads(thread_id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS execution_turn_id TEXT,
  ADD COLUMN IF NOT EXISTS action_state TEXT,
  ADD COLUMN IF NOT EXISTS action_error TEXT,
  ADD COLUMN IF NOT EXISTS action_updated_at BIGINT;

CREATE INDEX IF NOT EXISTS idx_today_cards_execution_thread
  ON today_cards(agent_id, execution_thread_id);

UPDATE _schema_version SET version = 27;
