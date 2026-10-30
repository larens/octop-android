ALTER TABLE today_cards ADD COLUMN risk_level TEXT NOT NULL DEFAULT 'A2'
  CHECK (risk_level IN ('A0', 'A1', 'A2', 'A3', 'A4'));

CREATE TABLE today_card_evidence (
  agent_id         TEXT NOT NULL,
  card_id          TEXT NOT NULL,
  evidence_id      TEXT NOT NULL,
  source_type      TEXT NOT NULL,
  source_record_id TEXT,
  event_type       TEXT NOT NULL,
  occurred_at      BIGINT NOT NULL,
  privacy_class    TEXT NOT NULL,
  url              TEXT,
  title            TEXT,
  excerpt          TEXT,
  created_at       BIGINT NOT NULL,
  PRIMARY KEY (agent_id, card_id, evidence_id),
  FOREIGN KEY (agent_id, card_id) REFERENCES today_cards(agent_id, card_id) ON DELETE CASCADE
);

CREATE INDEX idx_today_card_evidence_card
  ON today_card_evidence(agent_id, card_id, created_at DESC);

UPDATE _schema_version SET version = 29;
