CREATE TABLE today_card_feedback (
  feedback_id   TEXT PRIMARY KEY,
  agent_id      TEXT NOT NULL,
  card_id       TEXT NOT NULL,
  user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  feedback_type TEXT NOT NULL CHECK (feedback_type IN ('useful', 'irrelevant', 'too_frequent', 'poor_source')),
  created_at    INTEGER NOT NULL,
  FOREIGN KEY (agent_id, card_id) REFERENCES today_cards(agent_id, card_id) ON DELETE CASCADE
);

CREATE INDEX idx_today_feedback_card_created
  ON today_card_feedback(agent_id, card_id, created_at DESC);
CREATE INDEX idx_today_feedback_user_type
  ON today_card_feedback(user_id, feedback_type, created_at DESC);

UPDATE _schema_version SET version = 25;
