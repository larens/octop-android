ALTER TABLE interest_monitors
  ADD COLUMN IF NOT EXISTS delivery_backoff_until BIGINT,
  ADD COLUMN IF NOT EXISTS feedback_backoff_reason TEXT,
  ADD COLUMN IF NOT EXISTS feedback_backoff_level INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS interest_source_preferences (
  agent_id           TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  user_id            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  domain             TEXT NOT NULL,
  poor_source_count  INTEGER NOT NULL DEFAULT 0,
  muted_until        BIGINT,
  updated_at         BIGINT NOT NULL,
  PRIMARY KEY (agent_id, user_id, domain)
);

CREATE INDEX IF NOT EXISTS idx_interest_source_preferences_muted
  ON interest_source_preferences(agent_id, user_id, muted_until);

UPDATE _schema_version SET version = 28;
