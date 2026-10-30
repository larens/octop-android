CREATE TABLE today_goals (
  goal_id            TEXT PRIMARY KEY,
  agent_id           TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  user_id            INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title              TEXT NOT NULL,
  definition         TEXT NOT NULL,
  success_rules      TEXT NOT NULL,
  source             TEXT NOT NULL DEFAULT 'user_explicit' CHECK (source IN ('user_explicit', 'agent_suggested')),
  status             TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused', 'completed')),
  review_at          INTEGER,
  last_confirmed_at  INTEGER NOT NULL,
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL
);

ALTER TABLE interest_monitors ADD COLUMN goal_id TEXT REFERENCES today_goals(goal_id) ON DELETE SET NULL;
ALTER TABLE today_cards ADD COLUMN goal_id TEXT REFERENCES today_goals(goal_id) ON DELETE SET NULL;

CREATE INDEX idx_today_goals_owner_status ON today_goals(agent_id, user_id, status, updated_at DESC);
CREATE INDEX idx_interest_monitors_goal ON interest_monitors(goal_id);
CREATE INDEX idx_today_cards_goal ON today_cards(goal_id, updated_at DESC);

UPDATE _schema_version SET version = 26;
