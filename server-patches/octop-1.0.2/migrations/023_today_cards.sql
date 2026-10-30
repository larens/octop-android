CREATE TABLE today_cards (
  agent_id       TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
  card_id        TEXT NOT NULL,
  user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  dedupe_key     TEXT NOT NULL,
  kind           TEXT NOT NULL,
  title          TEXT NOT NULL,
  summary        TEXT NOT NULL,
  why_now        TEXT NOT NULL,
  confidence     REAL NOT NULL,
  lifecycle      TEXT NOT NULL CHECK (lifecycle IN ('unread', 'read', 'snoozed', 'dismissed')),
  occurred_at    INTEGER NOT NULL,
  snoozed_until  INTEGER,
  action_prompt  TEXT NOT NULL,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL,
  PRIMARY KEY (agent_id, card_id),
  UNIQUE (agent_id, dedupe_key)
);

CREATE TABLE today_evidence (
  agent_id  TEXT NOT NULL,
  card_id   TEXT NOT NULL,
  event_id  TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (agent_id, card_id, event_id),
  FOREIGN KEY (agent_id, card_id) REFERENCES today_cards(agent_id, card_id) ON DELETE CASCADE,
  FOREIGN KEY (agent_id, event_id) REFERENCES mobile_events(agent_id, event_id) ON DELETE CASCADE
);

CREATE INDEX idx_today_cards_agent_lifecycle_updated
  ON today_cards(agent_id, lifecycle, updated_at DESC);
CREATE INDEX idx_today_evidence_event
  ON today_evidence(agent_id, event_id);

UPDATE _schema_version SET version = 23;
