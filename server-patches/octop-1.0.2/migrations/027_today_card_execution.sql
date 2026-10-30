ALTER TABLE today_cards ADD COLUMN execution_thread_id TEXT REFERENCES threads(thread_id) ON DELETE SET NULL;
ALTER TABLE today_cards ADD COLUMN execution_turn_id TEXT;
ALTER TABLE today_cards ADD COLUMN action_state TEXT;
ALTER TABLE today_cards ADD COLUMN action_error TEXT;
ALTER TABLE today_cards ADD COLUMN action_updated_at INTEGER;

CREATE INDEX idx_today_cards_execution_thread
  ON today_cards(agent_id, execution_thread_id);

UPDATE _schema_version SET version = 27;
